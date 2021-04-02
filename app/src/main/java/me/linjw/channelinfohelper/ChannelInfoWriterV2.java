package me.linjw.channelinfohelper;


import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

public class ChannelInfoWriterV2 implements IChannelInfoWriter {
    private void changeSocdOffset(ByteBuffer eocd, String channelInfo) {
        // 由于APK签名块在socd offset的前面
        // 而我们又在APK签名块里面插入了渠道信息
        // 所以socd offset应该再往后移动插入的渠道信息键值对的大小


        // 读取原本的socd offset
        eocd.position(Utils.EOCD_POSITION_SOCD_OFFSET);
        int originOffset = eocd.getInt();


        // 键值对格式如下:
        //
        // 键值对长度(不包含自己的8个字节)   8字节
        // ID                            4字节
        // Value                         键值对长度-ID的4字节
        //
        // 所以应该加上键值对长度(8字节)、ID长度(4字节)、渠道信息长度
        eocd.position(Utils.EOCD_POSITION_SOCD_OFFSET);
        eocd.putInt(originOffset + Long.BYTES + Integer.BYTES + channelInfo.getBytes().length);
    }

    @Override
    public boolean addChannelInfo(String srcApk, String outputApk, String channelInfo) {
        // [APK签名块]插入在[central directory]之前,而[central directory]的起始位置可以在[EOCD]的socdOffset部分读取
        // 我们在[APK签名块]里面插入渠道信息,会影响到[central directory]的位置,
        // 所以需要同步修改[EOCD]里面的socdOffset
        //
        // [zip包其余内容](不变)          ...
        //
        //                              1. APK签名块大小(不包含自己的8个字节)        8字节
        // [APK签名块](需要插入渠道信息)   2. ID-Value键值对                        大小可变
        //                              3. APK签名块大小(和第1部分相等)             8字节
        //                              4. 魔法数(固定为字符串"APK Sig Block 42")  16字节
        //                                      <--------------------------
        // [central directory](不变)    ...                                |
        //                                                                 |
        //                              end of central dir signature       |
        //                              ...                                |
        // [EOCD](需要修改socdOffset)    socdOffset  ------------------------
        //                              ...
        //

        if (channelInfo == null || channelInfo.isEmpty()) {
            return true;
        }

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
            ByteBuffer eocd = Utils.findEocd(srcChannel);
            if (eocd == null) {
                return false;
            }

            // 获取旧的APK签名块
            long socdOffset = Utils.getSocdOffset(eocd);
            Utils.Pair<Long, ByteBuffer> oldSignV2Block = Utils.getSignV2Block(zipFile, socdOffset);
            if (oldSignV2Block == null) {
                return false;
            }

            // 往APK签名块插入渠道信息,得到新的APK签名块
            ByteBuffer newSignV2Block = addChannelInfo(oldSignV2Block.second, channelInfo);

            // 修改eocd中的socd
            changeSocdOffset(eocd, channelInfo);

            // APK签名块前的数据是没有改过的,可以直接拷贝
            srcChannel.position(0);
            Utils.copyByLength(srcChannel, dstChannel, oldSignV2Block.first);

            // 往后插入新的APK签名块的数据
            dstChannel.write(newSignV2Block);

            // 往后插入[central directory]的数据,这部分也是没有修改的
            srcChannel.position(socdOffset);
            Utils.copyByLength(srcChannel, dstChannel, srcChannel.size() - socdOffset - eocd.capacity());

            // 往后插入修改后的eocd
            eocd.position(0);
            dstChannel.write(eocd);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            Utils.safeClose(srcChannel, zipFile, dstChannel, fos);
        }
        return true;
    }

    private static ByteBuffer addChannelInfo(ByteBuffer oldSignV2BlockSize, String channelInfo) {
        // ID-Value键值对的格式如下:
        //
        // 键值对长度(不包含自己的8个字节)   8字节
        // ID                            4字节
        // Value                         键值对长度-ID的4字节

        // 所以整个ID-Value键的长度应该是 Value长度 + ID长度(4字节) + 键值对长度(8字节)
        long infoLength = channelInfo.getBytes().length;
        long channelBlockRealSize = Long.BYTES + Integer.BYTES + infoLength;


        ByteBuffer buffer = ByteBuffer.allocate((int) (oldSignV2BlockSize.capacity() + channelBlockRealSize));
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // 先将原本的APK完整拷贝出来
        oldSignV2BlockSize.position(0);
        buffer.put(oldSignV2BlockSize);

        // 读取原本的APK签名块长度
        oldSignV2BlockSize.position(0);
        long originSize = oldSignV2BlockSize.getLong();

        // 该长度要加上插入的渠道信息键值对长度
        buffer.position(0);
        buffer.putLong(originSize + channelBlockRealSize);

        // APK签名块结构如下:
        //
        // 1. APK签名块大小(不包含自己的8个字节)        8字节
        // 2. ID-Value键值对                        大小可变
        // 3. APK签名块大小(和第1部分相等)             8字节
        // 4. 魔法数(固定为字符串"APK Sig Block 42")  16字节

        // 我们把渠道包键值对放到整个APK签名块的最后
        // 所以从后往前减去魔法数的16字节,减去APK签名块大小的8字节
        // 定位到渠道包键值的起始位置
        long magicNumberSize = Utils.SIG_V2_MAGIC_NUMBER.getBytes().length;
        buffer.position((int) (oldSignV2BlockSize.capacity() - magicNumberSize - Long.BYTES));

        // 插入渠道包键值对数据
        buffer.putLong(infoLength + Integer.BYTES);
        buffer.putInt(Utils.CHANNEL_INFO_SIG);
        buffer.put(channelInfo.getBytes());

        // 插入APK签名块长度
        buffer.putLong(originSize + channelBlockRealSize);

        // 插入魔法数
        buffer.put(Utils.SIG_V2_MAGIC_NUMBER.getBytes());

        buffer.flip();
        return buffer;
    }

}
