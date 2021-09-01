package cn.entertech.sdk

interface IManager {
    fun isDeviceAvailable(): Boolean

    fun isDeviceHasPermission(): Boolean

    fun requestPermission(callback: Callback)

    fun onOpenDevice()

    fun addBrainDataListener(listener: (ByteArray) -> Unit)

    fun removeBrainDataListener(listener: (ByteArray) -> Unit)

    fun addContactDataListener(listener: (Int) -> Unit)

    fun removeContactDataListener(listener: (Int) -> Unit)


}