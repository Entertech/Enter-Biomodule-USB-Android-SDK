package cn.entertech.sdk

interface IManager {
    fun isDeviceAvailable(): Boolean

    fun isDeviceHasPermission(): Boolean

    fun requestPermission(callback: Callback)

    fun onOpenDevice()

    fun startCollection(): Boolean

    fun stopCollection(): Boolean

    fun addBrainDataListener(listener: (ByteArray) -> Unit)

    fun removeBrainDataListener(listener: (ByteArray) -> Unit)

    fun addContactDataListener(listener: (Int) -> Unit)

    fun removeContactDataListener(listener: (Int) -> Unit)

    fun addHeartRateDataListener(listener: (Int) -> Unit)

    fun removeHeartRateDataListener(listener: (Int) -> Unit)
}