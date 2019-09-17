package cn.entertech.biomoduleusbsdk;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

import cn.entertech.sdk.Callback;
import cn.entertech.sdk.EnterBiomoduleUsbManager;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public class MainActivity extends AppCompatActivity {

    private EnterBiomoduleUsbManager enterBiomoduleUsbManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
    }

    public void onStartCollection(View view) {
        enterBiomoduleUsbManager.startCollection();
    }

    public void onStopCollection(View view) {
        enterBiomoduleUsbManager.stopCollection();
    }
}
