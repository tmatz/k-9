package com.fsck.k9.activity.misc;

import java.util.ArrayList;

import com.fsck.k9.K9;

import android.app.Activity;
import android.util.Log;

public class NonConfigurationList implements NonConfigurationInstance {
    ArrayList<Object> mArrayList;

    public NonConfigurationList(Activity activity) {
        mArrayList = new ArrayList<Object>();
    }

    public boolean add(NonConfigurationInstance o) {
        return mArrayList.add(o);
    }

    public int size() {
        return mArrayList.size();
    }

    @Override
    public void restore(Activity activity) {
        Log.e(K9.LOG_TAG, String.format("NonConfigurationList#restore() called"));
        for (Object o : mArrayList) {
            Log.e(K9.LOG_TAG, String.format("NonConfigurationList#restore() loop"));
            ((NonConfigurationInstance)o).restore(activity);
        }
    }

    @Override
    public boolean retain() {
        Log.e(K9.LOG_TAG, String.format("NonConfigurationList#retain() called"));
        boolean retain = false;
        for (Object o : mArrayList) {
            Log.e(K9.LOG_TAG, String.format("NonConfigurationList#retain() loop"));
            boolean tmp = ((NonConfigurationInstance)o).retain();
            if (tmp) {
                retain = true;
            }
        }

        return retain;
    }

    public void clear(Class<?> klass) {
        Log.e(K9.LOG_TAG, String.format("NonConfigurationList#clear() called"));

        for (int i = 0; i < mArrayList.size(); ) {
            if (klass.isInstance(mArrayList.get(i))) {
                Log.e(K9.LOG_TAG, String.format("NonConfigurationList#clear() loop"));
                mArrayList.remove(i);
            }
            else {
                i++;
            }
        }
    }
}
