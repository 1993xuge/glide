package com.xuge.gradletest;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        normalUse(this);
    }

    private void normalUse(Context context) {
        String url = "http://img1.dzwww.com:8080/tupian_pl/20150813/16/7858995348613407436.jpg";
        ImageView imageView = (ImageView) findViewById(R.id.imageView);
        Glide.with(context)
                .load(url)
                .override(300, 300)
//                .centerCrop()
                .fitCenter()
                .centerInside()
                .into(imageView);
    }

    private void normalUseWithPlace(Context context) {
        String url = "http://img1.dzwww.com:8080/tupian_pl/20150813/17/7858995348613407436.jpg";
        ImageView imageView = (ImageView) findViewById(R.id.imageView);
        Glide.with(context)
                .load(url)
                .placeholder(R.drawable.default_image)
                .error(R.drawable.error_image)
                .into(imageView);
    }
}
