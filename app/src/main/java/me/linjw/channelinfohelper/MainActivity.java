package me.linjw.channelinfohelper;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textView = findViewById(R.id.text);
        IChannelInfoReader reader = new ChannelInfoReader();
        textView.setText("ChannelInfo : " + reader.getChannelInfo(this));
    }
}
