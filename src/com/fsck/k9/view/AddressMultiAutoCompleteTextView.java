package com.fsck.k9.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.MultiAutoCompleteTextView;
import com.fsck.k9.mail.Address;

public class AddressMultiAutoCompleteTextView extends MultiAutoCompleteTextView {

    public AddressMultiAutoCompleteTextView(Context context) {
        super(context);
    }

    public AddressMultiAutoCompleteTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AddressMultiAutoCompleteTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    protected void replaceText(CharSequence text) {
        Address address = new Address(text.toString());
        super.replaceText(address.getAddress());
    }
}
