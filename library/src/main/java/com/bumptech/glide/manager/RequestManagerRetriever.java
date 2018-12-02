package com.bumptech.glide.manager;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.util.ArrayMap;
import android.util.Log;
import android.view.View;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.util.Preconditions;
import com.bumptech.glide.util.Util;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A collection of static methods for creating new {@link com.bumptech.glide.RequestManager}s or
 * retrieving existing ones from activities and fragment.
 */
public class RequestManagerRetriever implements Handler.Callback {
    @VisibleForTesting
    static final String FRAGMENT_TAG = "com.bumptech.glide.manager";
    private static final String TAG = "RMRetriever";

    private static final int ID_REMOVE_FRAGMENT_MANAGER = 1;
    private static final int ID_REMOVE_SUPPORT_FRAGMENT_MANAGER = 2;

    // Hacks based on the implementation of FragmentManagerImpl in the non-support libraries that
    // allow us to iterate over and retrieve all active Fragments in a FragmentManager.
    private static final String FRAGMENT_INDEX_KEY = "key";

    /**
     * The top application level RequestManager.
     */
    private volatile RequestManager applicationManager;

    /**
     * Pending adds for RequestManagerFragments.
     */
    @SuppressWarnings("deprecation")
    @VisibleForTesting
    final Map<android.app.FragmentManager, RequestManagerFragment> pendingRequestManagerFragments =
            new HashMap<>();

    /**
     * Pending adds for SupportRequestManagerFragments.
     */
    @VisibleForTesting
    final Map<FragmentManager, SupportRequestManagerFragment> pendingSupportRequestManagerFragments =
            new HashMap<>();

    /**
     * Main thread handler to handle cleaning up pending fragment maps.
     */
    private final Handler handler;
    private final RequestManagerFactory factory;

    // Objects used to find Fragments and Activities containing views.
    private final ArrayMap<View, Fragment> tempViewToSupportFragment = new ArrayMap<>();
    private final ArrayMap<View, android.app.Fragment> tempViewToFragment = new ArrayMap<>();
    private final Bundle tempBundle = new Bundle();

    public RequestManagerRetriever(@Nullable RequestManagerFactory factory) {
        this.factory = factory != null ? factory : DEFAULT_FACTORY;
        handler = new Handler(Looper.getMainLooper(), this /* Callback */);
    }

    /**
     * 通过 double-check的方式获取全局唯一的 RequestManager实例对象applicationManager
     */
    @NonNull
    private RequestManager getApplicationManager(@NonNull Context context) {
        // Either an application context or we're on a background thread.
        if (applicationManager == null) {
            synchronized (this) {
                if (applicationManager == null) {
                    // Normally pause/resume is taken care of by the fragment we add to the fragment or
                    // activity. However, in this case since the manager attached to the application will not
                    // receive lifecycle events, we must force the manager to start resumed using
                    // ApplicationLifecycle.

                    // TODO(b/27524013): Factor out this Glide.get() call.
                    Glide glide = Glide.get(context.getApplicationContext());
                    // 通过factory构造RequestManager实例
                    applicationManager =
                            factory.build(
                                    glide,
                                    new ApplicationLifecycle(),
                                    new EmptyRequestManagerTreeNode(),
                                    context.getApplicationContext());
                }
            }
        }
        return applicationManager;
    }

    @NonNull
    public RequestManager get(@NonNull Context context) {
        if (context == null) {
            // context为null，抛出异常
            throw new IllegalArgumentException("You cannot start a load on a null Context");
        } else if (Util.isOnMainThread() && !(context instanceof Application)) {
            // 如果当前是主线程 并且 Context不是Application
            if (context instanceof FragmentActivity) {
                // get(FragmentActivity activity)
                return get((FragmentActivity) context);
            } else if (context instanceof Activity) {
                // get(Activity activity)
                return get((Activity) context);
            } else if (context instanceof ContextWrapper) {
                //
                return get(((ContextWrapper) context).getBaseContext());
            }
        }

        // 非主线程，或 Context 是 Application，会调用getApplicationManager方法获取唯一的RequestManager实例
        return getApplicationManager(context);
    }

    @NonNull
    public RequestManager get(@NonNull FragmentActivity activity) {
        if (Util.isOnBackgroundThread()) {
            return get(activity.getApplicationContext());
        } else {
            assertNotDestroyed(activity);
            FragmentManager fm = activity.getSupportFragmentManager();
            return supportFragmentGet(
                    activity, fm, /*parentHint=*/ null, isActivityVisible(activity));
        }
    }

    @NonNull
    public RequestManager get(@NonNull Fragment fragment) {
        Preconditions.checkNotNull(fragment.getActivity(),
                "You cannot start a load on a fragment before it is attached or after it is destroyed");
        if (Util.isOnBackgroundThread()) {
            // 是后台线程，则将ApplicationContext作为参数传递给get(Context)方法
            return get(fragment.getActivity().getApplicationContext());
        } else {
            // 是主线程，仍然获取一个FragmentManager对象并将其作为参数传递给supportFragmentGet
            // FragmentManager对象的作用是将一个隐藏的Fragment添加到当前环境中，用来进行生命周期的检查
            FragmentManager fm = fragment.getChildFragmentManager();
            return supportFragmentGet(fragment.getActivity(), fm, fragment, fragment.isVisible());
        }
    }

    @SuppressWarnings("deprecation")
    @NonNull
    public RequestManager get(@NonNull Activity activity) {
        // 是否是后台线程
        if (Util.isOnBackgroundThread()) {
            // 是后台线程，则将ApplicationContext作为参数传递给get(Context)方法
            return get(activity.getApplicationContext());
        } else {
            assertNotDestroyed(activity);
            // 是主线程，则获取Activity的FragmentManager，并作为参数传递给fragmentGet方法
            android.app.FragmentManager fm = activity.getFragmentManager();
            return fragmentGet(
                    activity, fm, /*parentHint=*/ null, isActivityVisible(activity));
        }
    }

    @SuppressWarnings("deprecation")
    @NonNull
    public RequestManager get(@NonNull View view) {
        if (Util.isOnBackgroundThread()) {
            // 是后台线程，则将ApplicationContext作为参数传递给get(Context)方法
            return get(view.getContext().getApplicationContext());
        }

        // 对View和与其关联的Context进行非空检查
        Preconditions.checkNotNull(view);
        Preconditions.checkNotNull(view.getContext(),
                "Unable to obtain a request manager for a view without a Context");
        // 获取Activity对象，并进行非空判断
        Activity activity = findActivity(view.getContext());
        // The view might be somewhere else, like a service.
        if (activity == null) {
            return get(view.getContext().getApplicationContext());
        }

        // Support Fragments.
        // Although the user might have non-support Fragments attached to FragmentActivity, searching
        // for non-support Fragments is so expensive pre O and that should be rare enough that we
        // prefer to just fall back to the Activity directly.
        if (activity instanceof FragmentActivity) {
            // 当前Activity是FragmentActivity
            Fragment fragment = findSupportFragment(view, (FragmentActivity) activity);
            // 如果能够获取到Fragment，并且不为null，则调用get(Fragment fragment)，否则调用get(Activity activity)
            return fragment != null ? get(fragment) : get(activity);
        }

        // Standard Fragments.
        android.app.Fragment fragment = findFragment(view, activity);
        if (fragment == null) {
            // get(Activity activity)
            return get(activity);
        }

        // 调用get(android.app.Fragment fragment)
        return get(fragment);
    }

    private static void findAllSupportFragmentsWithViews(
            @Nullable Collection<Fragment> topLevelFragments,
            @NonNull Map<View, Fragment> result) {
        if (topLevelFragments == null) {
            return;
        }
        for (Fragment fragment : topLevelFragments) {
            // getFragment()s in the support FragmentManager may contain null values, see #1991.
            if (fragment == null || fragment.getView() == null) {
                continue;
            }
            result.put(fragment.getView(), fragment);
            findAllSupportFragmentsWithViews(fragment.getChildFragmentManager().getFragments(), result);
        }
    }

    @Nullable
    private Fragment findSupportFragment(@NonNull View target, @NonNull FragmentActivity activity) {
        tempViewToSupportFragment.clear();
        findAllSupportFragmentsWithViews(
                activity.getSupportFragmentManager().getFragments(), tempViewToSupportFragment);
        Fragment result = null;
        View activityRoot = activity.findViewById(android.R.id.content);
        View current = target;
        while (!current.equals(activityRoot)) {
            result = tempViewToSupportFragment.get(current);
            if (result != null) {
                break;
            }
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }

        tempViewToSupportFragment.clear();
        return result;
    }

    @SuppressWarnings({"deprecation", "DeprecatedIsStillUsed"})
    @Deprecated
    @Nullable
    private android.app.Fragment findFragment(@NonNull View target, @NonNull Activity activity) {
        tempViewToFragment.clear();
        findAllFragmentsWithViews(activity.getFragmentManager(), tempViewToFragment);

        android.app.Fragment result = null;

        View activityRoot = activity.findViewById(android.R.id.content);
        View current = target;
        while (!current.equals(activityRoot)) {
            result = tempViewToFragment.get(current);
            if (result != null) {
                break;
            }
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        tempViewToFragment.clear();
        return result;
    }

    // TODO: Consider using an accessor class in the support library package to more directly retrieve
    // non-support Fragments.
    @SuppressWarnings("deprecation")
    @Deprecated
    @TargetApi(Build.VERSION_CODES.O)
    private void findAllFragmentsWithViews(
            @NonNull android.app.FragmentManager fragmentManager,
            @NonNull ArrayMap<View, android.app.Fragment> result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            for (android.app.Fragment fragment : fragmentManager.getFragments()) {
                if (fragment.getView() != null) {
                    result.put(fragment.getView(), fragment);
                    findAllFragmentsWithViews(fragment.getChildFragmentManager(), result);
                }
            }
        } else {
            findAllFragmentsWithViewsPreO(fragmentManager, result);
        }
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    private void findAllFragmentsWithViewsPreO(
            @NonNull android.app.FragmentManager fragmentManager,
            @NonNull ArrayMap<View, android.app.Fragment> result) {
        int index = 0;
        while (true) {
            tempBundle.putInt(FRAGMENT_INDEX_KEY, index++);
            android.app.Fragment fragment = null;
            try {
                fragment = fragmentManager.getFragment(tempBundle, FRAGMENT_INDEX_KEY);
            } catch (Exception e) {
                // This generates log spam from FragmentManager anyway.
            }
            if (fragment == null) {
                break;
            }
            if (fragment.getView() != null) {
                result.put(fragment.getView(), fragment);
                if (VERSION.SDK_INT >= VERSION_CODES.JELLY_BEAN_MR1) {
                    findAllFragmentsWithViews(fragment.getChildFragmentManager(), result);
                }
            }
        }
    }

    @Nullable
    private Activity findActivity(@NonNull Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper) {
            return findActivity(((ContextWrapper) context).getBaseContext());
        } else {
            return null;
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private static void assertNotDestroyed(@NonNull Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            throw new IllegalArgumentException("You cannot start a load for a destroyed activity");
        }
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    @NonNull
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public RequestManager get(@NonNull android.app.Fragment fragment) {
        if (fragment.getActivity() == null) {
            throw new IllegalArgumentException(
                    "You cannot start a load on a fragment before it is attached");
        }
        if (Util.isOnBackgroundThread() || Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // 后台线程，或当前的sdk版本小与17
            // 则将ApplicationContext作为参数传递给get(Context)方法
            return get(fragment.getActivity().getApplicationContext());
        } else {
            android.app.FragmentManager fm = fragment.getChildFragmentManager();
            return fragmentGet(fragment.getActivity(), fm, fragment, fragment.isVisible());
        }
    }

    @SuppressWarnings("deprecation")
    @Deprecated
    @NonNull
    RequestManagerFragment getRequestManagerFragment(Activity activity) {
        return getRequestManagerFragment(
                activity.getFragmentManager(), /*parentHint=*/ null, isActivityVisible(activity));
    }

    @SuppressWarnings("deprecation")
    @NonNull
    private RequestManagerFragment getRequestManagerFragment(
            @NonNull final android.app.FragmentManager fm,
            @Nullable android.app.Fragment parentHint,
            boolean isParentVisible) {
        // 根据tag在传入的FragmentManager中查找 RequestManagerFragment
        RequestManagerFragment current = (RequestManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            // 没有找到，从pendingRequestManagerFragments中获取缓存的
            current = pendingRequestManagerFragments.get(fm);
            if (current == null) {
                // 没有缓存的RequestManagerFragment，那么就通过构造方法构造一个
                current = new RequestManagerFragment();
                current.setParentFragmentHint(parentHint);
                if (isParentVisible) {
                    // 如果这个RequestManagerFragment需要加入的parent可见，则回调onStart
                    current.getGlideLifecycle().onStart();
                }
                // 缓存RequestManagerFragment对象，key为FragmentManager，value为RequestManagerFragment对象
                pendingRequestManagerFragments.put(fm, current);
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();

                // ？？？？
                handler.obtainMessage(ID_REMOVE_FRAGMENT_MANAGER, fm).sendToTarget();
            }
        }
        return current;
    }

    @SuppressWarnings({"deprecation", "DeprecatedIsStillUsed"})
    @Deprecated
    @NonNull
    private RequestManager fragmentGet(@NonNull Context context,
                                       @NonNull android.app.FragmentManager fm,
                                       @Nullable android.app.Fragment parentHint,
                                       boolean isParentVisible) {
        // 1、通过getRequestManagerFragment获取一个可用的RequestManagerFragment对象
        RequestManagerFragment current = getRequestManagerFragment(fm, parentHint, isParentVisible);

        // 2、获取RequestManagerFragment中的成员变量RequestManager
        RequestManager requestManager = current.getRequestManager();
        if (requestManager == null) {
            // TODO(b/27524013): Factor out this Glide.get() call.
            // 获取失败，则通过factory构造出一个requestManager，并将其设置给current
            Glide glide = Glide.get(context);
            requestManager =
                    factory.build(
                            glide, current.getGlideLifecycle(), current.getRequestManagerTreeNode(), context);
            current.setRequestManager(requestManager);
        }
        return requestManager;
    }

    @NonNull
    SupportRequestManagerFragment getSupportRequestManagerFragment(FragmentActivity activity) {
        return getSupportRequestManagerFragment(
                activity.getSupportFragmentManager(), /*parentHint=*/ null, isActivityVisible(activity));
    }

    private static boolean isActivityVisible(Activity activity) {
        // This is a poor heuristic, but it's about all we have. We'd rather err on the side of visible
        // and start requests than on the side of invisible and ignore valid requests.
        return !activity.isFinishing();
    }

    @NonNull
    private SupportRequestManagerFragment getSupportRequestManagerFragment(
            @NonNull final FragmentManager fm, @Nullable Fragment parentHint, boolean isParentVisible) {
        // 根据tag在传入的FragmentManager中查找 SupportRequestManagerFragment
        SupportRequestManagerFragment current =
                (SupportRequestManagerFragment) fm.findFragmentByTag(FRAGMENT_TAG);
        if (current == null) {
            // 没有找到，从 pendingSupportRequestManagerFragments 中获取缓存的
            current = pendingSupportRequestManagerFragments.get(fm);
            if (current == null) {
                // 没有缓存的 SupportRequestManagerFragment，那么就通过构造方法构造一个
                current = new SupportRequestManagerFragment();
                current.setParentFragmentHint(parentHint);
                if (isParentVisible) {
                    // 如果这个 SupportRequestManagerFragment 需要加入的parent可见，则回调onStart
                    current.getGlideLifecycle().onStart();
                }
                // 缓存SupportRequestManagerFragment对象，key为FragmentManager，value为SupportRequestManagerFragment对象
                pendingSupportRequestManagerFragments.put(fm, current);
                fm.beginTransaction().add(current, FRAGMENT_TAG).commitAllowingStateLoss();
                handler.obtainMessage(ID_REMOVE_SUPPORT_FRAGMENT_MANAGER, fm).sendToTarget();
            }
        }
        return current;
    }

    @NonNull
    private RequestManager supportFragmentGet(
            @NonNull Context context,
            @NonNull FragmentManager fm,
            @Nullable Fragment parentHint,
            boolean isParentVisible) {
        // 1、获取SupportRequestManagerFragment对象
        SupportRequestManagerFragment current =
                getSupportRequestManagerFragment(fm, parentHint, isParentVisible);

        // 2、获取SupportRequestManagerFragment中的成员变量RequestManager
        RequestManager requestManager = current.getRequestManager();
        if (requestManager == null) {
            // TODO(b/27524013): Factor out this Glide.get() call.
            // 获取失败，则通过factory构造出一个requestManager，并将其设置给current
            Glide glide = Glide.get(context);
            requestManager =
                    factory.build(
                            glide, current.getGlideLifecycle(), current.getRequestManagerTreeNode(), context);
            current.setRequestManager(requestManager);
        }
        return requestManager;
    }

    @Override
    public boolean handleMessage(Message message) {
        boolean handled = true;
        Object removed = null;
        Object key = null;
        switch (message.what) {
            case ID_REMOVE_FRAGMENT_MANAGER:
                android.app.FragmentManager fm = (android.app.FragmentManager) message.obj;
                key = fm;
                removed = pendingRequestManagerFragments.remove(fm);
                break;
            case ID_REMOVE_SUPPORT_FRAGMENT_MANAGER:
                FragmentManager supportFm = (FragmentManager) message.obj;
                key = supportFm;
                removed = pendingSupportRequestManagerFragments.remove(supportFm);
                break;
            default:
                handled = false;
                break;
        }
        if (handled && removed == null && Log.isLoggable(TAG, Log.WARN)) {
            Log.w(TAG, "Failed to remove expected request manager fragment, manager: " + key);
        }
        return handled;
    }

    /**
     * Used internally to create {@link RequestManager}s.
     */
    public interface RequestManagerFactory {
        @NonNull
        RequestManager build(
                @NonNull Glide glide,
                @NonNull Lifecycle lifecycle,
                @NonNull RequestManagerTreeNode requestManagerTreeNode,
                @NonNull Context context);
    }

    private static final RequestManagerFactory DEFAULT_FACTORY = new RequestManagerFactory() {
        @NonNull
        @Override
        public RequestManager build(@NonNull Glide glide, @NonNull Lifecycle lifecycle,
                                    @NonNull RequestManagerTreeNode requestManagerTreeNode, @NonNull Context context) {
            return new RequestManager(glide, lifecycle, requestManagerTreeNode, context);
        }
    };
}
