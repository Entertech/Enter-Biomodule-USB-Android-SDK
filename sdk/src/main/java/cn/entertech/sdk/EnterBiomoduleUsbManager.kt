package cn.entertech.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import cn.entertech.sdk.StringUtils.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnterBiomoduleUsbManager(private var context: Context) : IManager {
    private var singleThreadExecutor: ExecutorService? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbEndpointOut: UsbEndpoint? = null
    private var mUsbEndpointIn: UsbEndpoint? = null
    private var mUsbInterface: UsbInterface? = null
    private var mPermissionCallback: Callback? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbManager: UsbManager? = null
    private var mMainHandler: Handler

    private val brainDataListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val contactListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val heartRateDataListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    init {
        mUsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        mMainHandler = Handler(Looper.getMainLooper())
        singleThreadExecutor = Executors.newSingleThreadExecutor()

    }

    companion object {
        private const val ACTION_DEVICE_PERMISSION = "actionDevicePermission"
        private const val DEVICE_PRODUCT_ID = 60000
        private const val DEVICE_VENDOR_ID = 4292
        private const val COMMAND_START_BRAIN_COLLECTION = "01"
        private const val COMMAND_STOP_BRAIN_COLLECTION = "02"
        private const val BRAIN_PACKAGE_LENGTH = 29 * 2
        private const val HEART_PACKAGE_LENGTH = 13 * 2
        private const val PACKAGE_FLAG_HEAD = "BBBBBB"
        private const val PACKAGE_FLAG_TAIL = "FFFFFF"
        private const val PACKAGE_FLAG_BRAIN = "1D"
        private const val PACKAGE_FLAG_HEART = "0D"

        @Volatile
        var mEnterBiomoduleUsbManager: EnterBiomoduleUsbManager? = null

        fun getInstance(context: Context): EnterBiomoduleUsbManager {
            if (mEnterBiomoduleUsbManager == null) {
                synchronized(EnterBiomoduleUsbManager::class.java) {
                    if (mEnterBiomoduleUsbManager == null) {
                        mEnterBiomoduleUsbManager = EnterBiomoduleUsbManager(context)
                    }
                }
            }
            return mEnterBiomoduleUsbManager!!
        }
    }

    override fun isDeviceAvailable(): Boolean {
        val deviceList = mUsbManager?.deviceList
        if (deviceList == null || deviceList.isEmpty()) {
            Log.d("USBManager", "no device found!")
            return false
        }

        val localIterator = deviceList.values.iterator()
        while (localIterator.hasNext()) {
            var localUsbDevice = localIterator.next()
            if (localUsbDevice.productId == DEVICE_PRODUCT_ID && localUsbDevice.vendorId == DEVICE_VENDOR_ID) {
                mUsbDevice = localUsbDevice
                return true
            }
        }
        return false
    }

    override fun isDeviceHasPermission(): Boolean {
        return mUsbManager!!.hasPermission(mUsbDevice)
    }

    override fun requestPermission(callback: Callback) {
        this.mPermissionCallback = callback
        var usbPermissionReceiver = UsbPermissionReceiver()
        val intent = Intent(ACTION_DEVICE_PERMISSION)
        val mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, 0)
        val permissionFilter = IntentFilter(ACTION_DEVICE_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, permissionFilter)
        mUsbManager!!.requestPermission(mUsbDevice, mPermissionIntent)
    }

    override fun onOpenDevice() {
        mUsbInterface = mUsbDevice?.getInterface(0)
        if (mUsbInterface != null) {
            for (index in 0 until mUsbInterface!!.getEndpointCount()) {
                var point = mUsbInterface!!.getEndpoint(index)
                if (point.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (point.direction == UsbConstants.USB_DIR_IN) {
                        mUsbEndpointIn = point
                    } else if (point.direction == UsbConstants.USB_DIR_OUT) {
                        mUsbEndpointOut = point
                    }
                }
            }
            mUsbDeviceConnection = mUsbManager!!.openDevice(mUsbDevice)
            mUsbDeviceConnection?.claimInterface(mUsbInterface, true)
            // reset
            mUsbDeviceConnection?.controlTransfer(0x40, 0, 0, 0, null, 0, 0)
            // clear Rx
            mUsbDeviceConnection?.controlTransfer(0x40, 0, 1, 0, null, 0, 0)
            // clear Tx
            mUsbDeviceConnection?.controlTransfer(0x40, 0, 2, 0, null, 0, 0)
            //Baud rate 115200
            mUsbDeviceConnection?.controlTransfer(0x40, 0x03, 0x001A, 0, null, 0, 0)

            singleThreadExecutor?.execute(dataReceiveRunnable)
        }
    }

    fun init() {
        init(null)
    }

    fun init(callback: Callback?) {
        if (isDeviceAvailable()) {
            if (isDeviceHasPermission()) {
                onOpenDevice()
                callback?.onSuccess()
            } else {
                requestPermission(object : Callback {
                    override fun onError(error: String) {
                        callback?.onError(error)
                    }

                    override fun onSuccess() {
                        onOpenDevice()
                        callback?.onSuccess()
                    }
                })
            }
        } else {
            callback?.onError("no device found!")
        }
    }

    override fun startCollection(): Boolean {
        return writeCommandToUsb(COMMAND_START_BRAIN_COLLECTION)
    }

    override fun stopCollection(): Boolean {
        return writeCommandToUsb(COMMAND_STOP_BRAIN_COLLECTION)
    }

    override fun addBrainDataListener(listener: (ByteArray) -> Unit) {
        brainDataListeners.add(listener)
    }

    override fun removeBrainDataListener(listener: (ByteArray) -> Unit) {
        brainDataListeners.remove(listener)
    }

    override fun addContactDataListener(listener: (Int) -> Unit) {
        contactListeners.add(listener)
    }

    override fun removeContactDataListener(listener: (Int) -> Unit) {
        contactListeners.remove(listener)
    }

    override fun addHeartRateDataListener(listener: (Int) -> Unit) {
        heartRateDataListeners.add(listener)
    }

    override fun removeHeartRateDataListener(listener: (Int) -> Unit) {
        heartRateDataListeners.remove(listener)
    }


    private inner class UsbPermissionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_DEVICE_PERMISSION == action) {
                synchronized(UsbPermissionReceiver::class.java) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        //user choose YES for your previously popup window asking for grant permission for this usb device
                        mPermissionCallback?.onSuccess()
                    } else {
                        //user choose NO for your previously popup window asking for grant permission for this usb device
                        //user choose YES for your previously popup window asking for grant permission for this usb device
                        mPermissionCallback?.onError("permission not granted")
                    }
                }
            }
        }
    }

    private fun writeCommandToUsb(hexString: String): Boolean {
        if (mUsbDeviceConnection == null) {
            return false
        }
        val ret = mUsbDeviceConnection!!.bulkTransfer(mUsbEndpointOut, hexSringToBytes(hexString), hexSringToBytes(hexString).size, 0)
        return ret > 0
    }

    private var lastPackage: String = ""
    private var dataReceiveRunnable = Runnable {
        while (true) {
            val bytes = ByteArray(mUsbEndpointIn!!.maxPacketSize)
            val ret = mUsbDeviceConnection!!.bulkTransfer(mUsbEndpointIn, bytes, bytes.size, 0)
            if (ret > 0) {
                val data = toHexString(bytes)
                mMainHandler.post {
                    var formatData = removeLastZero(data)
                    //解析一个包正好是一个脑波数据包
                    if (formatData.length == BRAIN_PACKAGE_LENGTH && isHeadCorrect(formatData) && isTailCorrect(formatData)) {
                        parseBrain(formatData)
                    }
                    //解析一个包正好是一个心率数据包
                    if (formatData.length == HEART_PACKAGE_LENGTH && isHeadCorrect(formatData) && isTailCorrect(formatData)) {
                        parseHeart(formatData)
                    }
                    //解析一个包正好是一个心率数据包加脑波数据包
                    if (formatData.length == (BRAIN_PACKAGE_LENGTH + HEART_PACKAGE_LENGTH) && isHeadCorrect(formatData) && isTailCorrect(formatData)) {
                        parseBrainAndHeart(formatData)
                    }
                    //解析一个心率数据包被分割的情况
                    if (formatData.length < HEART_PACKAGE_LENGTH) {
                        if (lastPackage == "") {
                            lastPackage = formatData
                        } else {
                            var finalString = lastPackage + formatData
                            lastPackage = ""
                            if (finalString.length == HEART_PACKAGE_LENGTH && isHeadCorrect(finalString) && isTailCorrect(finalString)) {
                                parseHeart(finalString)
                            }
                        }
                    }
                    //解析一个包是部分心率数据加完整脑波数据包
                    if (formatData.length in (HEART_PACKAGE_LENGTH + 1) until (BRAIN_PACKAGE_LENGTH + HEART_PACKAGE_LENGTH)) {
                        if (lastPackage != "") {
                            var finalString = lastPackage + formatData
                            lastPackage = ""
                            if (finalString.length == (BRAIN_PACKAGE_LENGTH + HEART_PACKAGE_LENGTH) && isHeadCorrect(finalString) && isTailCorrect(finalString)) {
                                parseBrainAndHeart(finalString)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun removeLastZero(hexString: String): String {
        var data = hexString
        return if (data.substring(data.length - 1, data.length) == "0") {
            data = hexString.substring(0, data.length - 1)
            removeLastZero(data)
        } else {
            data
        }
    }

    private fun isHeadCorrect(data: String): Boolean {
        return data.length > 6 && data.substring(0, 6) == PACKAGE_FLAG_HEAD
    }

    private fun isTailCorrect(data: String): Boolean {
        return data.length > 6 && data.substring(data.length - 6, data.length) == PACKAGE_FLAG_TAIL
    }

    private fun getEffectiveBrainData(data: String): String {
        return data.substring(10, 50)
    }

    private fun getEffectiveHeartData(data: String): String {
        return data.substring(14, 16)
    }

    private fun getEffectiveContactData(data: String): String {
        return data.substring(16, 18)
    }

    private fun parseBrain(data: String) {
        var effectiveBrainData = getEffectiveBrainData(data)
        var brainBytes = hexSringToBytes(effectiveBrainData)
        brainDataListeners.forEach {
            it.invoke(brainBytes)
        }
    }

    private fun parseHeart(data: String) {
        var heartHexString = getEffectiveHeartData(data)
        var heart = byte2Unchart(hexSringToBytes(heartHexString)[0])
        heartRateDataListeners.forEach {
            it.invoke(heart)
        }
        var contactHexString = getEffectiveContactData(data)
        var contact = byte2Unchart(hexSringToBytes(contactHexString)[0])
        contactListeners.forEach {
            it.invoke(contact)
        }
    }

    private fun parseBrainAndHeart(data: String) {
        if (data.substring(6, 8) == PACKAGE_FLAG_BRAIN) {
            parseBrain(data.substring(0, BRAIN_PACKAGE_LENGTH))
            parseHeart(data.substring(BRAIN_PACKAGE_LENGTH, BRAIN_PACKAGE_LENGTH + HEART_PACKAGE_LENGTH))
        } else if (data.substring(6, 8) == PACKAGE_FLAG_HEART) {
            parseHeart(data.substring(0, HEART_PACKAGE_LENGTH))
            parseBrain(data.substring(HEART_PACKAGE_LENGTH, BRAIN_PACKAGE_LENGTH + HEART_PACKAGE_LENGTH))
        }
    }
}