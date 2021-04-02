package me.linjw.channelinfohelper;

import android.content.Context;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class ChannelInfoReaderV2 implements IChannelInfoReader {

    @Override
    public String getChannelInfo(Context context) {
        String apkPath = Utils.getApkPath(context);
        if (apkPath == null) {
            return null;
        }
        RandomAccessFile apk = null;
        try {
            apk = new RandomAccessFile(apkPath, "r");

            // 查找eocd
            ByteBuffer eocd = Utils.findEocd(apk.getChannel());
            if (eocd == null) {
                return null;
            }

            // 获取APK签名块
            Utils.Pair<Long, ByteBuffer> signV2Block = Utils.getSignV2Block(apk, Utils.getSocdOffset(eocd));
            if (signV2Block == null) {
                return null;
            }


            // APK签名块结构如下:
            //
            // 1. APK签名块大小(不包含自己的8个字节)        8字节
            // 2. ID-Value键值对(有多个键值对)            大小可变
            //      2.1 键值对长度(不包含自己的8个字节)     8字节
            //      2.2 ID                              4字节
            //      2.3 Value                           键值对长度-ID的4字节
            // 3. APK签名块大小(和第1部分相等)             8字节
            // 4. 魔法数(固定为字符串"APK Sig Block 42")  16字节


            int id;
            long length,realLength;
            long positionLimit = signV2Block.second.capacity()
                    - Long.BYTES                                    // APK签名块大小的长度(8字节)
                    - Utils.SIG_V2_MAGIC_NUMBER.getBytes().length;  // 结尾魔数的长度(16字节)

            int position = Long.BYTES; // 跳过开头APK签名块大小的8字节才是第一个ID-Value键值对

            do {
                signV2Block.second.position(position);

                // 读取键值对长度(不包含自己的8个字节)
                length = signV2Block.second.getLong();

                // 键值对长度是不包含长度信息的8个字节的,所以要加上这8个字节
                realLength = Long.BYTES + length;

                // 读取ID
                id = signV2Block.second.getInt();

                // 移动到下一个键值对
                position += realLength;

                // 判断是否找到渠道信息键值对的ID,或者已经遍历完整个APK签名块
            } while (id != Utils.CHANNEL_INFO_SIG && position <= positionLimit);

            if (id == Utils.CHANNEL_INFO_SIG) {
                // 如果可以找到渠道信息键值对,往后读取就可以读到渠道信息
                // 键值对长度是包含ID的四个字节的,要减去
                return Utils.readString(signV2Block.second, (int) (length - Integer.BYTES));
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Utils.safeClose(apk);
        }

        return null;
    }
}
