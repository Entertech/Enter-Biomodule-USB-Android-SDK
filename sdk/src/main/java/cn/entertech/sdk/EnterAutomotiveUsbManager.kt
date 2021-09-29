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
    private var mUsbManager: UsbManager? = null
    private var mMainHandler: Handler

    private val brainDataListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    private val contactListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val connectListener = CopyOnWriteArrayList<() -> Unit>()
    private val disconnectListener = CopyOnWriteArrayList<() -> Unit>()

    var mUsbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            var action = intent.action
            if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                var device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice
                if (device != null) {
                    if (device.productId == DEVICE_PRODUCT_ID && device.vendorId == DEVICE_VENDOR_ID) {
                        disconnectListener.forEach {
                            it.invoke()
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
                var device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as UsbDevice
                if (device != null) {
                    if (device.productId == DEVICE_PRODUCT_ID && device.vendorId == DEVICE_VENDOR_ID) {
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
        private const val DEVICE_PRODUCT_ID = 60000
        private const val DEVICE_VENDOR_ID = 4292
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
        val intent = Intent(ACTION_DEVICE_PERMISSION)
        val mPermissionIntent = PendingIntent.getBroadcast(context, 0, intent, 0)
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

    fun addConnectListener(listener: () -> Unit) {
        connectListener.add(listener)
    }

    fun removeConnectListener(listener: () -> Unit) {
        connectListener.remove(listener)
    }

    fun addDisconnectListener(listener: () -> Unit) {
        disconnectListener.add(listener)
    }

    fun removeDisconnectListener(listener: () -> Unit) {
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
        while (true) {
            val bytes = ByteArray(mUsbEndpointIn!!.maxPacketSize)
            val ret = mUsbDeviceConnection!!.bulkTransfer(mUsbEndpointIn, bytes, bytes.size, 100)
            if (ret > 0) {
                var data = String(bytes)
                var stringData = data.replace(String(ByteArray(1) { 0x00 }), "")
                var stringDataArray = stringData.split("\r\n")
                stringDataArray.forEach {
                    mMainHandler.post {
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
                        }
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

    private fun parseContact(data: String) {
        if (data == DATA_CONTACT_BAD) {
            contactListeners.forEach {
                it.invoke(0)
            }
        } else {
            contactListeners.forEach {
                it.invoke(1)
            }
        }
    }

}