package com.fsck.k9.activity.misc;

import java.util.ArrayList;

import com.fsck.k9.activity.FolderInfoHolder;
import com.fsck.k9.activity.FolderList;

import android.app.Activity;

public class NonConfigurationFolderAdapter implements NonConfigurationInstance {
    private ArrayList<FolderInfoHolder> mFolders;

    public NonConfigurationFolderAdapter(ArrayList<FolderInfoHolder> folders) {
        mFolders = folders;
    }

    @Override
    public void restore(Activity activity) {
        if (mFolders != null) {
            ((FolderList)activity).restoreFolders(mFolders);
            mFolders = null;
        }
    }

    @Override
    public boolean retain() {
        if (mFolders != null) {
            return true;
        }
        return false;
    }
}
