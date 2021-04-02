package me.linjw.channelinfohelper;

import android.content.Context;

import java.io.RandomAccessFile;

public class ChannelInfoReaderV1 implements IChannelInfoReader {

    @Override
    public String getChannelInfo(Context context) {
        String apkPath = Utils.getApkPath(context);
        if (apkPath == null) {
            return null;
        }
        RandomAccessFile apk = null;
        try {
            apk = new RandomAccessFile(apkPath, "r");

            // 读取apk的结尾4字节看看是否为渠道信息魔数判断是否有渠道信息
            long sigPosition = apk.length() - Integer.BYTES;
            int sig = Utils.readInt(apk, sigPosition);
            if (sig != Utils.CHANNEL_INFO_SIG) {
                return null;
            }

            // 再往前读两个字节获取渠道信息的长度
            long lengthPosition = sigPosition - Short.BYTES;
            short length = Utils.readShort(apk, lengthPosition);
            if (length <= 0) {
                return null;
            }

            // 根据长度读取渠道信息
            long infoPosition = lengthPosition - length;
            return Utils.readString(apk, infoPosition, length);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Utils.safeClose(apk);
        }

        return null;
    }
}
