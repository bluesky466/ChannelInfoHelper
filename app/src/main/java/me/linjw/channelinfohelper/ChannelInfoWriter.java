package me.linjw.channelinfohelper;

public class ChannelInfoWriter implements IChannelInfoWriter {
    private IChannelInfoWriter[] mWriter = new IChannelInfoWriter[]{
            new ChannelInfoWriterV2(),
            new ChannelInfoWriterV1()
    };

    @Override
    public boolean addChannelInfo(String srcApk, String outputApk, String channelInfo) {
        for (IChannelInfoWriter writer : mWriter) {
            if (writer.addChannelInfo(srcApk, outputApk, channelInfo)) {
                return true;
            }
        }
        return false;
    }
}
