package cn.wch.ch34xuartdriver;

import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class FileStoreHelper {

    public static FileStoreHelper mInstance = null;
    public static int converUnchart(byte data) {
        return (data & 0xff);
    }
    private Handler mHandler;
    private HandlerThread handlerThread;

    public FileStoreHelper() {
        handlerThread = new HandlerThread("store_file_thread");
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

//    public static FileStoreHelper getInstance() {
//        if (mInstance == null) {
//            synchronized (FileStoreHelper.class) {
//                if (mInstance == null) {
//                    mInstance = new FileStoreHelper();
//                }
//            }
//        }
//        return mInstance;
//    }

    private PrintWriter pw;
    private boolean isFirstWrite = true;

    public void setPath(String filePath, String fileName) {
        try {
            File file = createFile(filePath, fileName);
            pw = new PrintWriter(new FileWriter(file));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public File createFile(String filePath, String fileName) {
        File file = null;
        try {
            File dir = new File(filePath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            file = new File(filePath + File.separator + fileName+".txt");
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return file;
    }

    public void writeData(final String data) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isFirstWrite) {
                    pw.print(data);
                    isFirstWrite = false;
                } else {
                    pw.append(data);
                }
                pw.flush();
            }
        });
    }

}
