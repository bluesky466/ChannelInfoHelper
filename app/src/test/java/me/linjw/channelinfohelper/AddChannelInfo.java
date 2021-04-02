package me.linjw.channelinfohelper;

import org.junit.Test;

public class AddChannelInfo {
    private static final String SOURCE_APK_PATH = "/Users/LinJW/workspace/ChannelInfoHelper/app/release/app-release.apk";
    private static final String TARGET_APK_PATH = "/Users/LinJW/workspace/ChannelInfoHelper/app/release/app-release.channel.apk";
    private static final String CHANNEL_INFO = "TestChannelInfo";

    @Test
    public void addChannelInfo() {
        if (new ChannelInfoWriter().addChannelInfo(SOURCE_APK_PATH, TARGET_APK_PATH, CHANNEL_INFO)) {
            System.out.println("add ChannelInfo Success!");
        } else {
            System.out.println("add ChannelInfo Failed!");
        }
    }
}
