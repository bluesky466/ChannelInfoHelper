package me.linjw.channelinfohelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class ChannelInfoWriterV1 implements IChannelInfoWriter {
    @Override
    public boolean addChannelInfo(String srcApk, String outputApk, String channelInfo) {
        RandomAccessFile zipFile = null;
        FileOutputStream fos = null;
        FileChannel srcChannel = null;
        FileChannel dstChannel = null;
        try {
            zipFile = new RandomAccessFile(new File(srcApk), "r");
            srcChannel = zipFile.getChannel();

            fos = new FileOutputStream(outputApk);
            dstChannel = fos.getChannel();

            // 查找eocd
            ByteBuffer originEocd = Utils.findEocd(srcChannel);
            if (originEocd == null) {
                return false;
            }

            // 往eocd插入渠道信息得到新的eocd
            ByteBuffer newEocd = addChannelInfo(originEocd, channelInfo);

            // eocd前面的数据是没有改到的,直接拷贝就好
            Utils.copyByLength(srcChannel, dstChannel, zipFile.length() - originEocd.capacity());

            // 往后插入新的eocd
            dstChannel.write(newEocd);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            Utils.safeClose(srcChannel, zipFile, dstChannel, fos);
        }
        return true;
    }

    private static ByteBuffer addChannelInfo(ByteBuffer eocd, String channelInfo) {
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
        // 我们可以在.ZIP file comment里面插入渠道信息块:
        //
        // 渠道信息      大小记录在[渠道信息长度]中
        // 渠道信息长度  2字节
        // 魔数         4字节
        //
        // 魔数放在最后面方便我们读取判断是否有渠道信息

        short infoLength = (short) channelInfo.getBytes().length;
        short channelBlockSize = (short) (infoLength // 渠道信息
                + Short.BYTES      // 渠道信息长度
                + Integer.BYTES);  // 渠道信息魔数
        ByteBuffer buffer = ByteBuffer.allocate(eocd.capacity() + channelBlockSize);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // eocd前面部分的数据我们没有改动,直接拷贝就好
        byte[] bytes = new byte[Utils.EOCD_MIN_LENGTH - Utils.EOCD_SIZE_OF_COMMENT_LENGTH];
        eocd.get(bytes);
        buffer.put(bytes);

        // 由于插入了渠道信息块,zip包的注释长度需要相应的增加
        buffer.putShort((short) (eocd.getShort() + channelBlockSize));

        // 拷贝原本的zip包注释
        eocd.position(Utils.EOCD_MIN_LENGTH);
        buffer.put(eocd);

        // 插入渠道包信息块
        buffer.put(channelInfo.getBytes());     // 渠道信息
        buffer.putShort(infoLength);            // 渠道信息长度
        buffer.putInt(Utils.CHANNEL_INFO_SIG);  // 魔数

        buffer.flip();
        return buffer;
    }
}
