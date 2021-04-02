package me.linjw.channelinfohelper;

import android.content.Context;

public class ChannelInfoReader implements IChannelInfoReader {
    private IChannelInfoReader[] mReaders = new IChannelInfoReader[]{
            new ChannelInfoReaderV1(),
            new ChannelInfoReaderV2()
    };

    public String getChannelInfo(Context context) {
        for (IChannelInfoReader reader : mReaders) {
            String channelInfo = reader.getChannelInfo(context);
            if(channelInfo != null) {
                return channelInfo;
            }
        }
        return null;
    }
}
