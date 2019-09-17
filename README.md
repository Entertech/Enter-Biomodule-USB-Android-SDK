# Biomoudle USB SDK [![Download](https://api.bintray.com/packages/hzentertech/maven/biomoduleusb/images/download.svg?version=1.0.0-alpha)](https://bintray.com/hzentertech/maven/biomoduleusb/1.0.0-alpha/link)

## 简介

客户端接入该SDK，可方便的通过USB与采集模块通信。

## 依赖

在module的build.gradle文件中添加如下依赖：

```groovy
implementation 'cn.entertech:biomoduleusb:1.0.0-alpha'
```

在项目根目录的build.gradle文件中添加如下依赖：

```groovy
allprojects {
    repositories {
        maven {
            url "https://dl.bintray.com/hzentertech/maven"
        }
    }
}
```

## 权限申请

在module的AndroidManifest.xml文件中添加如下权限：

```xml
<uses-feature android:name="android.hardware.usb.host" />
<uses-permission android:name="android.hardware.usb.host" />
<uses-permission android:name="ANDROID.PERMISSION.HARDWARE_TEST" />

```

## 快速集成

通过`EnterBiomoduleUsbManager`管理类经过如下几步便可快速将SDK集成到自己的项目中。

### 1.初始化

```java
enterBiomoduleUsbManager = EnterBiomoduleUsbManager.Companion.getInstance(this);
enterBiomoduleUsbManager.init(new Callback() {
	@Override
	public void onSuccess() {
		Log.d("初始化：", "设备初始化成功");
    }

	@Override
	public void onError(@NotNull String error) {
		Log.d("初始化：", "设备初始化失败：" + error);
	}
});
```

### 2.添加监听

采集模块采集到的数据有脑波数据、心率数据和佩戴检测信号，如要获取相应的数据，只需添加对应的监听即可

```java
enterBiomoduleUsbManager.addBrainDataListener(new Function1<byte[], Unit>() {
	@Override
    public Unit invoke(byte[] bytes) {
    	Log.d("脑波数据：", Arrays.toString(bytes));
        return null;
    }
});
enterBiomoduleUsbManager.addHeartRateDataListener(new Function1<Integer, Unit>() {
	@Override
	public Unit invoke(Integer integer) {
		Log.d("心率数据：", integer + "");
        return null;
    }
});
enterBiomoduleUsbManager.addContactDataListener(new Function1<Integer, Unit>() {
	@Override
	public Unit invoke(Integer integer) {
		Log.d("佩戴检测：", integer + "");
        return null;
    }
});
```

> 注意：当页面退出或者不需要接受相应的数据时，需要通过调用enterBiomoduleUsbManager.remove…Listener方法来移除对应的监听，否则会有内存泄漏的风险。

### 3.开始采集

```java
boolean result = enterBiomoduleUsbManager.startCollection();
log.d("开始采集指令是否下发成功",result)
```

### 4.停止采集

```java
boolean result = enterBiomoduleUsbManager.stopCollection();
log.d("停止采集指令是否下发成功",result)
```

