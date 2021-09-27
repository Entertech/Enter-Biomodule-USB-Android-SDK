package cn.entertech.biomoduleusbsdk;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import cn.entertech.sdk.Callback;
import cn.entertech.sdk.EnterAutomotiveUsbManager;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

public class MainActivity extends AppCompatActivity {

    private EnterAutomotiveUsbManager enterAutomotiveUsbManager;
    private Function0<Unit> connectListener;
    private Function0<Unit> disconnectListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enterAutomotiveUsbManager = EnterAutomotiveUsbManager.Companion.getInstance(this);
        enterAutomotiveUsbManager.init(29987,6790,new Callback() {
            @Override
            public void onSuccess() {
                Toast.makeText(MainActivity.this,"设备初始化成功",Toast.LENGTH_SHORT).show();
                Log.d("初始化：", "设备初始化成功");
            }

            @Override
            public void onError(@NotNull String error) {
                Toast.makeText(MainActivity.this,"设备初始化失败",Toast.LENGTH_SHORT).show();
                Log.d("初始化：", "设备初始化失败：" + error);
            }
        });
        enterAutomotiveUsbManager.addBrainDataListener(new Function1<byte[], Unit>() {
            @Override
            public Unit invoke(final byte[] bytes) {
                Log.d("脑波数据：", Arrays.toString(bytes));
                return null;
            }
        });
        enterAutomotiveUsbManager.addContactDataListener(new Function1<Integer, Unit>() {
            @Override
            public Unit invoke(final Integer integer) {
                Log.d("佩戴检测：", integer + "");
                return null;
            }
        });

        connectListener = new Function0<Unit>() {
            @Override
            public Unit invoke() {
                enterAutomotiveUsbManager.init(29987,6790,new Callback() {
                    @Override
                    public void onSuccess() {
                        Toast.makeText(MainActivity.this,"设备初始化成功",Toast.LENGTH_SHORT).show();
                        Log.d("初始化：", "设备初始化成功");
                    }

                    @Override
                    public void onError(@NotNull String error) {
                        Toast.makeText(MainActivity.this,"设备初始化失败",Toast.LENGTH_SHORT).show();
                        Log.d("初始化：", "设备初始化失败：" + error);
                    }
                });
                Log.d("连接状态",   "usb 插入");
                return null;
            }
        };

        disconnectListener =new Function0<Unit>() {
            @Override
            public Unit invoke() {
                Log.d("连接状态",   "usb 拔出");
                return null;
            }
        };
        enterAutomotiveUsbManager.addConnectListener(connectListener);
        enterAutomotiveUsbManager.addDisconnectListener(disconnectListener);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d("######","onStart");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.d("######","onStop");
    }

    @Override
    protected void onDestroy() {
        enterAutomotiveUsbManager.removeConnectListener(connectListener);
        enterAutomotiveUsbManager.removeDisconnectListener(disconnectListener);
        enterAutomotiveUsbManager.release();
        super.onDestroy();
    }
}
