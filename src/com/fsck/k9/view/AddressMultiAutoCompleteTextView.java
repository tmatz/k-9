package com.fsck.k9.view;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.MultiAutoCompleteTextView;

import com.fsck.k9.K9;
import com.fsck.k9.mail.Address;

public class AddressMultiAutoCompleteTextView extends MultiAutoCompleteTextView {

    final static boolean addressOnly = false;

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
        String result = text.toString();

        Address address = new Address(text.toString());
        String name = address.getPersonal();
        if (name == null || K9.getRecipientAddressFormat() == K9.RecipientAddressFormat.ADDRESS_ONLY) {
            result = address.getAddress();
        }
        super.replaceText(result);
    }
}
