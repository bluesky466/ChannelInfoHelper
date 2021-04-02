package me.linjw.channelinfohelper;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

public class Utils {
    // end of central directory record 的格式如下:
    //
    // end of central dir signature                                                    4 bytes  (0x06054b50)
    // number of this disk                                                             2 bytes
    // number of the disk with the start of the central directory                      2 bytes
    // total number of entries in the central directory on this disk                   2 bytes
    // total number of entries in the central directory                                2 bytes
    // size of the central directory                                                   4 bytes
    // offset of start of central directory with respect to the starting disk number   4 bytes
    // .ZIP file comment length                                                        2 bytes
    // .ZIP file comment                                                               (variable size)
    //
    // 前面22个字节是固定的,最后的[.ZIP file comment]长度是可变。可以为零,此时即为EOCD的最小长度22字节。
    // 又由于它的长度必须保存在[.ZIP file comment length]里面,所以它最长两个字节的最大值,即0xffff。
    // 再加上前面的22个字节就是EOCD的最大长度
    public static final int EOCD_MIN_LENGTH = 22;
    public static final int EOCD_MAX_LENGTH = 0xffff + 22;
    public static final int EOCD_SIZE_OF_COMMENT_LENGTH = 2;
    public static final int EOCD_SIG = 0x06054b50;
    public static final int EOCD_POSITION_SOCD_OFFSET = 16;

    public static final int CHANNEL_INFO_SIG = 0x06054b51;

    public static final String SIG_V2_MAGIC_NUMBER = "APK Sig Block 42";

    private static ByteBuffer sReadBuffer;

    static {
        sReadBuffer = ByteBuffer.allocate(8);
        sReadBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public static void safeClose(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static String getApkPath(Context context) {
        try {
            return context.getPackageManager().getApplicationInfo(context.getPackageName(), 0).sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String readString(RandomAccessFile file, long position, int length) throws IOException {
        byte[] bytes = new byte[length];
        file.seek(position);
        file.read(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static long readLong(RandomAccessFile file, long position) throws IOException {
        sReadBuffer.clear();
        file.seek(position);
        file.read(sReadBuffer.array(), 0, Long.BYTES);
        return sReadBuffer.getLong();
    }

    public static int readInt(RandomAccessFile file, long position) throws IOException {
        sReadBuffer.clear();
        file.seek(position);
        file.read(sReadBuffer.array(), 0, Integer.BYTES);
        return sReadBuffer.getInt();
    }

    public static short readShort(RandomAccessFile file, long position) throws IOException {
        sReadBuffer.clear();
        file.seek(position);
        file.read(sReadBuffer.array(), 0, Short.BYTES);
        return sReadBuffer.getShort();
    }

    public static String readString(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static ByteBuffer readByLength(ByteBuffer src, int length) {
        byte[] bytes = new byte[length];
        src.get(bytes);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    public static void copyByLength(FileChannel srcChannel, FileChannel dstChannel, long length)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
        long total = 0;
        while (srcChannel.read(buffer) != -1) {
            buffer.flip();
            long totalReady = total + buffer.limit();
            if (totalReady < length) {
                dstChannel.write(buffer);
            } else {
                ByteBuffer remainder = readByLength(buffer, (int) (length - total));
                dstChannel.write(remainder);
                break;
            }
            buffer.clear();
            total = totalReady;
        }
        srcChannel.position(length);
    }

    public static ByteBuffer findEocd(FileChannel zipFile) throws IOException {
        // end of central directory record 是整个zip包的结尾
        // 而且它以0x06054b50这个魔数做起始,所以只需从后往前遍历找到这个魔数,即可截取整个EOCD
        //
        // [zip包其余内容]      ...
        //
        // [EOCD]              end of central dir signature (0x06054b50)
        //                     eocd其余部分

        try {
            if (zipFile.size() < Utils.EOCD_MIN_LENGTH) {
                return null;
            }

            // .ZIP file comment length只有2字节,所以描述长度最多有0xffff
            // 然后加上eocd前固定的22个字节就得到eocd可能的最大长度
            int length = (int) Math.min(Utils.EOCD_MAX_LENGTH, zipFile.size());
            ByteBuffer buffer = ByteBuffer.allocate(length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            zipFile.read(buffer, zipFile.size() - length);

            for (int i = length - Utils.EOCD_MIN_LENGTH; i >= 0; i--) {
                if (buffer.getInt(i) == Utils.EOCD_SIG) {
                    buffer.position(i);
                    return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
                }
            }
            System.out.println("return null");
            return null;
        } finally {
            zipFile.position(0);
        }
    }

    public static Pair<Long, ByteBuffer> getSignV2Block(RandomAccessFile apk, long socdOffset) throws IOException {
        // [APK签名块]插入在[central directory]之前,而[central directory]的起始位置可以在[EOCD]的socdOffset部分读取
        //
        //   [zip包其余内容]      ...
        //
        //                       1. APK签名块大小(不包含自己的8个字节)        8字节
        //   [APK签名块]          2. ID-Value键值对                        大小可变
        //                       3. APK签名块大小(和第1部分相等)             8字节
        //                       4. 魔法数(固定为字符串"APK Sig Block 42")  16字节
        //                                      <--------------------
        // [central directory]   ...                                |
        //                                                          |
        //                       end of central dir signature       |
        //                       ...                                |
        //    [EOCD]             socdOffset  ------------------------
        //                       ...
        //


        // 我们在socdOffset的位置往前读16个字节应该就能读到APK签名块的魔数
        int magicNumberSize = SIG_V2_MAGIC_NUMBER.getBytes().length;
        long magicNumberPosition = socdOffset - magicNumberSize;
        String magicNumber = Utils.readString(apk, magicNumberPosition, magicNumberSize);
        if (!Utils.SIG_V2_MAGIC_NUMBER.equals(magicNumber)) {
            System.out.println("Not find SIG V2 MAGIC NUMBER");
            return null;
        }

        // 再往前读8个字节应该可以读到APK签名块的大小
        long signV2BlockSize = Utils.readLong(apk, magicNumberPosition - Long.BYTES);

        // 由于APK签名块的大小不包含开头第1部分的8个字节
        // 所以再加上这8个字节才是APK签名块的真正大小
        long signV2BlockRealSize = signV2BlockSize + Long.BYTES;

        // 读取第1部分验证与前面读到的signV2BlockSize应该要相等
        long signV2BlockBegin = socdOffset - signV2BlockSize - Long.BYTES;
        if (signV2BlockSize != Utils.readLong(apk, signV2BlockBegin)) {
            System.out.println("signV2BlockSize error");
            return null;
        }

        // 读取APK签名块的内容,为了简单我们假设这个大小不超过int的最大值
        // 实际上int的最大值可以表示近2G的大小,apk签名块的大小基本不可能超过
        ByteBuffer signV2Block = ByteBuffer.allocate((int) (signV2BlockRealSize));
        signV2Block.order(ByteOrder.LITTLE_ENDIAN);
        apk.seek(signV2BlockBegin);
        apk.read(signV2Block.array());

        return new Pair<>(signV2BlockBegin, signV2Block);
    }

    public static long getSocdOffset(ByteBuffer eocd) {
        // 根据eocd结构可以知道socd offset的偏移是16
        eocd.position(EOCD_POSITION_SOCD_OFFSET);
        return  eocd.getInt();
    }

    public static class Pair<F, S> {
        F first;
        S second;

        Pair(F f, S s) {
            first = f;
            second = s;
        }
    }
}
