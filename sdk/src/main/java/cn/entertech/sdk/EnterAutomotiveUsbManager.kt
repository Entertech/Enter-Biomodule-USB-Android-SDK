package cn.entertech.sdk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.entertech.sdk.StringUtils.hexSringToBytes
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EnterAutomotiveUsbManager(private var context: Context) : IManager {
    private var singleThreadExecutor: ExecutorService? = null
    private var mUsbDeviceConnection: UsbDeviceConnection? = null
    private var mUsbEndpointOut: UsbEndpoint? = null
    private var mUsbEndpointIn: UsbEndpoint? = null
    private var mUsbInterface: UsbInterface? = null
    private var mPermissionCallback: Callback? = null
    private var mUsbDevice: UsbDevice? = null
    private var mCurrentProductId: Int? = null
    private var mCurrentVendorId: Int? = null
    private var mUsbManager: UsbManager? = null
    private var mMainHandler: Handler
    private val brainDataListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val contactListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val connectListener = CopyOnWriteArrayList<() -> Unit>()
    private val disconnectListener = CopyOnWriteArrayList<() -> Unit>()
    var logHelper = LogHelper

    @Volatile
    var isReadData = false
    var mUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var action = intent.action
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                var device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice
                if (device != null) {
                    if (isNewUsb(device.productId, device.vendorId) || isOldUsb(device.productId, device.vendorId)) {
                        logHelper.d("usb disconnect")
                        stop()
                        disconnectListener.forEach {
                            it.invoke()
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                var device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice
                if (device != null) {
                    if (isNewUsb(device.productId, device.vendorId) || isOldUsb(device.productId, device.vendorId)) {
                        logHelper.d("usb connect")
                        connectListener.forEach {
                            it.invoke()
                        }
                    }
                }
            }
        }
    }

    init {
        mUsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        mMainHandler = Handler(Looper.getMainLooper())
        singleThreadExecutor = Executors.newSingleThreadExecutor()
        logHelper.tag = "EnterAutomotiveUsbManager"
        val usbDeviceStateFilter = IntentFilter()
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        usbDeviceStateFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        context.registerReceiver(mUsbReceiver, usbDeviceStateFilter)
        val permissionFilter = IntentFilter(ACTION_DEVICE_PERMISSION)
        var usbPermissionReceiver = UsbPermissionReceiver()
        context.registerReceiver(usbPermissionReceiver, permissionFilter)
    }

    companion object {
        private const val ACTION_DEVICE_PERMISSION = "actionDevicePermission"
        private const val DEVICE_PRODUCT_ID = 29987
        private const val DEVICE_VENDOR_ID = 6790
        private const val DEVICE_PRODUCT_ID_OLD = 60000
        private const val DEVICE_VENDOR_ID_OLD = 4292
        private const val BRAIN_PACKAGE_LENGTH = 7
        private const val PACKAGE_FLAG_HEAD = "E"
        private const val DATA_CONTACT_BAD = "ffffff"

        @Volatile
        var mEnterAutomotiveUsbManager: EnterAutomotiveUsbManager? = null

        fun getInstance(context: Context): EnterAutomotiveUsbManager {
            if (mEnterAutomotiveUsbManager == null) {
                synchronized(EnterAutomotiveUsbManager::class.java) {
                    if (mEnterAutomotiveUsbManager == null) {
                        mEnterAutomotiveUsbManager = EnterAutomotiveUsbManager(context)
                    }
                }
            }
            return mEnterAutomotiveUsbManager!!
        }
    }

    fun setDebug(isDebug: Boolean) {
        logHelper.isDebug = isDebug
    }

    override fun isDeviceAvailable(): Boolean {
        val deviceList = mUsbManager?.deviceList
        if (deviceList == null || deviceList.isEmpty()) {
            logHelper.d("no device found!")
            return false
        }

        val localIterator = deviceList.values.iterator()
        while (localIterator.hasNext()) {
            var localUsbDevice = localIterator.next()
            if (isNewUsb(localUsbDevice.productId, localUsbDevice.vendorId) || isOldUsb(localUsbDevice.productId, localUsbDevice.vendorId)) {
                logHelper.d("find device:product id ${localUsbDevice.productId},vendor id ${localUsbDevice.vendorId}")
                mUsbDevice = localUsbDevice
                mCurrentProductId = localUsbDevice.productId
                mCurrentVendorId = localUsbDevice.vendorId
                return true
            }
        }
        return false
    }

    override fun isDeviceHasPermission(): Boolean {
        logHelper.d("isDeviceHasPermission:${mUsbManager!!.hasPermission(mUsbDevice)}")
        return mUsbManager!!.hasPermission(mUsbDevice)
    }

    override fun requestPermission(callback: Callback) {
        logHelper.d("requestPermission")
        this.mPermissionCallback = callback
        val intent = Intent(ACTION_DEVICE_PERMISSION)
        val mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, 0)
        mUsbManager!!.requestPermission(mUsbDevice, mPermissionIntent)
    }

    override fun onOpenDevice() {
        logHelper.d("onOpenDevice")
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
            if (mCurrentProductId == null || mCurrentVendorId == null) {
                return
            }
            if (isNewUsb(mCurrentProductId!!, mCurrentVendorId!!)) {
                configUsb(115200)
            } else {
                // reset
                mUsbDeviceConnection?.controlTransfer(0x40, 0, 0, 0, null, 0, 0)
//            // clear Rx
                mUsbDeviceConnection?.controlTransfer(0x40, 0, 1, 0, null, 0, 0)
//            // clear Tx
                mUsbDeviceConnection?.controlTransfer(0x40, 0, 2, 0, null, 0, 0)
//            //Baud rate 115200
                mUsbDeviceConnection?.controlTransfer(0x40, 0x03, 0x001A, 0, null, 0, 0)
            }
        }
    }

    private fun isNewUsb(productId: Int, vendorId: Int): Boolean {
        return productId == DEVICE_PRODUCT_ID && vendorId == DEVICE_VENDOR_ID
    }

    private fun isOldUsb(productId: Int, vendorId: Int): Boolean {
        return productId == DEVICE_PRODUCT_ID_OLD && vendorId == DEVICE_VENDOR_ID_OLD
    }

    private fun configUsb(paramInt: Int): Boolean {
        val arrayOfByte = ByteArray(8)
        mUsbDeviceConnection?.controlTransfer(192, 95, 0, 0, arrayOfByte, 8, 1000)
        mUsbDeviceConnection?.controlTransfer(64, 161, 0, 0, null, 0, 1000)
        var l1 = 1532620800 / paramInt.toLong()
        var i = 3
        while (true) {
            if (l1 <= 65520L || i <= 0) {
                val l2 = 65536L - l1
                val j: Int = (0xFF00 and l2.toInt() or i)
                val k: Int = (0xFF and l2.toInt())
                mUsbDeviceConnection?.controlTransfer(64, 154, 4882, j, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 154, 3884, k, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(192, 149, 9496, 0, arrayOfByte, 8, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 154, 1304, 80, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 161, 20511, 55562, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 154, 4882, j, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 154, 3884, k, null, 0, 1000)
                mUsbDeviceConnection?.controlTransfer(64, 164, 0, 0, null, 0, 1000)
                return true
            }
            l1 = l1 shr 3
            i--
        }
    }

    fun init() {
        init(null)
    }

    fun init(callback: Callback?) {
        logHelper.d("init usb device...")
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

    override fun addBrainDataListener(listener: (ByteArray) -> Unit) {
        logHelper.d("addBrainDataListener")
        brainDataListeners.add(listener)
    }

    override fun removeBrainDataListener(listener: (ByteArray) -> Unit) {
        logHelper.d("removeBrainDataListener")
        brainDataListeners.remove(listener)
    }

    override fun addContactDataListener(listener: (Int) -> Unit) {
        logHelper.d("addContactDataListener")
        contactListeners.add(listener)
    }

    override fun removeContactDataListener(listener: (Int) -> Unit) {
        logHelper.d("removeContactDataListener")
        contactListeners.remove(listener)
    }

    fun addConnectListener(listener: () -> Unit) {
        logHelper.d("addConnectListener")
        connectListener.add(listener)
    }

    fun removeConnectListener(listener: () -> Unit) {
        logHelper.d("removeConnectListener")
        connectListener.remove(listener)
    }

    fun addDisconnectListener(listener: () -> Unit) {
        logHelper.d("addDisconnectListener")
        disconnectListener.add(listener)
    }

    fun removeDisconnectListener(listener: () -> Unit) {
        logHelper.d("removeDisconnectListener")
        disconnectListener.remove(listener)
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
        logHelper.d("read usb data")
        while (isReadData) {
            val bytes = ByteArray(mUsbEndpointIn!!.maxPacketSize)
            val ret = mUsbDeviceConnection!!.bulkTransfer(mUsbEndpointIn, bytes, bytes.size, 100)
            if (ret > 0) {
                var data = String(bytes)
                var stringData = data.replace(String(ByteArray(1) { 0x00 }), "")
                var stringDataArray = stringData.split("\r\n")
                stringDataArray.forEach {
//                    mMainHandler.post {
                    //解析一个包正好是一个脑波数据包
                    if (it.length == BRAIN_PACKAGE_LENGTH && isHeadCorrect(it)) {
                        parseBrain(it)
                    }
                    //解析一个数据包被分割的情况
                    if (it.length < BRAIN_PACKAGE_LENGTH) {
                        if (lastPackage == "") {
                            lastPackage = it
                        } else {
                            var finalString = lastPackage + it
                            lastPackage = ""
                            if (finalString.length == BRAIN_PACKAGE_LENGTH && isHeadCorrect(finalString)) {
                                parseBrain(finalString)
                            }
                        }
//                        }
                    }
                }

            }
        }
    }


    private fun isHeadCorrect(data: String): Boolean {
        return data.length >= 1 && data.substring(0, 1) == PACKAGE_FLAG_HEAD
    }

    private fun getEffectiveBrainData(data: String): String {
        return data.substring(1, 7)
    }

    private fun parseBrain(data: String) {
        try {
            var effectiveBrainData = getEffectiveBrainData(data)
            var brainBytes = hexSringToBytes(effectiveBrainData)
            brainDataListeners.forEach {
                it.invoke(brainBytes)
            }
            parseContact(effectiveBrainData)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var contactDataBuffer = CopyOnWriteArrayList<String>()
    private fun parseContact(data: String) {
        var isContactGood = true
        contactDataBuffer.add(data)
        if (contactDataBuffer.size > 12) {
            for (i in 0 until contactDataBuffer.size - 12) {
                contactDataBuffer.removeAt(0)
            }
            for (data in contactDataBuffer) {
                if (data == DATA_CONTACT_BAD) {
                    isContactGood = false
                    break
                }
            }
            if (isContactGood) {
                contactListeners.forEach {
                    it.invoke(1)
                }
            } else {
                contactListeners.forEach {
                    it.invoke(0)
                }
            }
        }
    }

    @Synchronized
    fun start() {
        contactDataBuffer.clear()
        isReadData = true
        singleThreadExecutor?.execute(dataReceiveRunnable)
    }

    @Synchronized
    fun stop() {
        isReadData = false
        contactDataBuffer.clear()
    }

}