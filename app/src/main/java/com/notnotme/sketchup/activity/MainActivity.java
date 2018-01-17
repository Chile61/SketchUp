package com.notnotme.sketchup.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.text.emoji.EmojiCompat;
import android.support.v4.app.ShareCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.view.animation.AnimationUtils;
import android.widget.ViewSwitcher;

import com.notnotme.sketchup.Callback;
import com.notnotme.sketchup.R;
import com.notnotme.sketchup.Utils;
import com.notnotme.sketchup.dao.Sketch;
import com.notnotme.sketchup.fragment.AlbumFragment;
import com.notnotme.sketchup.fragment.SketchFragment;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends BaseActivity
        implements SketchFragment.SketchFragmentCallback, AlbumFragment.AlbumFragmentCallback {

    private static final String TAG = MainActivity.class.getSimpleName();

    public final static String ARG_SKETCH = TAG + ".arg_sketch";

    private final static String STATE_SWITCHER = TAG + ".switcher";

    private final static int SWITCHER_SKETCH = 0;
    private final static int SWITCHER_ALBUM = 1;

    private ViewSwitcher mViewSwitcher;
    private AlertDialog mAlertDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mViewSwitcher = findViewById(R.id.switcher);

        if (savedInstanceState != null) {
            switch (savedInstanceState.getInt(STATE_SWITCHER)) {
                case SWITCHER_ALBUM: showAlbumFragment(); break;
                case SWITCHER_SKETCH: showSketchFragment(); break;
            }
        } else {
            showSketchFragment();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.dismiss();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SWITCHER, mViewSwitcher.getDisplayedChild());
    }

    @Override
    public void onBackPressed() {
        if (mViewSwitcher.getDisplayedChild() == SWITCHER_ALBUM) {
            AlbumFragment albumFragment = (AlbumFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_album);
            if (albumFragment.isInEditMode()) {
                albumFragment.exitEditMode();
                return;
            } else {
                showSketchFragment();
                return;
            }
        }

        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String path = intent.getStringExtra(ARG_SKETCH);
        if (path != null) {
            SketchFragment sketchFragment = (SketchFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_sketch);
            sketchFragment.setSketch(path);

            if (mViewSwitcher.getDisplayedChild() != SWITCHER_SKETCH) {
                showSketchFragment();
            }
        }
    }

    @Override
    public void showSketchFragment() {
        mViewSwitcher.setInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_1));
        mViewSwitcher.setOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_1));
        mViewSwitcher.setDisplayedChild(SWITCHER_SKETCH);
    }

    @Override
    public void showAlbumFragment() {
        mViewSwitcher.setInAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_in_2));
        mViewSwitcher.setOutAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_out_2));
        mViewSwitcher.setDisplayedChild(SWITCHER_ALBUM);
    }

    @Override
    public void showSketch(Sketch sketch) {
        Intent viewSketchIntent = new Intent(this, SketchViewActivity.class);
        viewSketchIntent.putExtra(SketchViewActivity.ARG_SKETCH, sketch);
        startActivity(viewSketchIntent);
    }

    @Override
    public void showSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    public void saveSketch(Bitmap bitmap) {
        AsyncTask.execute(() -> {
            if (isDestroyed() || isFinishing()) return;

            AtomicReference<String> picturePath = new AtomicReference<>();
            AtomicReference<Exception> exceptionAtomicReference = new AtomicReference<>();
            try {
                long now = new Date().getTime();

                picturePath.set(Utils.saveImageToExternalStorage(this, String.valueOf(now), bitmap).getPath());
                getLocalDatabase().getDaoManager().saveSketch(new Sketch(picturePath.get(), now));
            } catch (Exception e) {
                exceptionAtomicReference.set(e);
            }

            getMainHandler().post(() -> {
                if (isDestroyed() || isFinishing()) return;

                Exception exception = exceptionAtomicReference.get();
                if (exception != null) {
                    mAlertDialog = new AlertDialog.Builder(this)
                            .setMessage(exception.getMessage())
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else {
                    AlbumFragment albumFragment = (AlbumFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_album);
                    albumFragment.loadSketches(null);

                    Snackbar.make(findViewById(R.id.coordinator),
                            EmojiCompat.get().process(getString(R.string.sketch_saved, "\uD83D\uDE02")),
                            Snackbar.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void deleteSketches(List<Sketch> sketches, Callback<List<Sketch>> callback) {
        AsyncTask.execute(() -> {
            if (isDestroyed() || isFinishing()) return;

            AtomicReference<Exception> exceptionAtomicReference = new AtomicReference<>();
            try {
                getLocalDatabase().getDaoManager().deleteSketches(sketches);
                for (Sketch sketch : sketches) {
                    Utils.deleteFile(this, new File(sketch.getPath()));
                }
            } catch (Exception e) {
                exceptionAtomicReference.set(e);
            }

            getMainHandler().post(() -> {
                if (isDestroyed() || isFinishing()) return;

                Exception exception = exceptionAtomicReference.get();
                if (exception != null) {
                    callback.failure(exception);
                } else {
                    callback.success(sketches);
                    Snackbar.make(findViewById(R.id.coordinator),
                            EmojiCompat.get().process(getString(R.string.sketch_deleted, String.valueOf(sketches.size()), "\uD83D\uDCA5")),
                            Snackbar.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void shareSketches(List<Sketch> sketches) {
        if (sketches.isEmpty()) return;

        ShareCompat.IntentBuilder shareIntentBuilder = ShareCompat.IntentBuilder.from(this).setType("image/png");

        if (sketches.size() > 1) {
            for (Sketch sketch : sketches) {
                shareIntentBuilder.addStream(FileProvider
                        .getUriForFile(this, getPackageName() + ".provider", new File(sketch.getPath())));
            }
        } else {
            shareIntentBuilder.setStream(FileProvider
                    .getUriForFile(this, getPackageName() + ".provider", new File(sketches.get(0).getPath())));
        }

        if (shareIntentBuilder.getIntent().resolveActivity(getPackageManager()) != null){
            startActivity(shareIntentBuilder.createChooserIntent());
        }
    }

    @Override
    public void getAllSketches(Callback<List<Sketch>> callback) {
        AsyncTask.execute(() -> {
            AtomicReference<Exception> exceptionAtomicReference = new AtomicReference<>();

            List<Sketch> sketchList = new ArrayList<>();
            try {
                sketchList.addAll(getLocalDatabase().getDaoManager().getAllSketch());
            } catch (Exception e) {
                exceptionAtomicReference.set(e);
            }

            getMainHandler().post(() -> {
                if (isDestroyed() || isFinishing()) return;

                Exception exception = exceptionAtomicReference.get();
                if (exception != null) {
                    callback.failure(exception);
                } else {
                    callback.success(sketchList);
                }
            });
        });
    }

}