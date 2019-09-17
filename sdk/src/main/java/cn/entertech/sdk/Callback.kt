package cn.entertech.sdk

interface Callback {
    fun onSuccess()
    fun onError(error:String)
}