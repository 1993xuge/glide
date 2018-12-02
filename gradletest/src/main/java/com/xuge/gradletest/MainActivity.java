package com.xuge.gradletest;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

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
                .fitCenter()
                .centerInside()
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
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
