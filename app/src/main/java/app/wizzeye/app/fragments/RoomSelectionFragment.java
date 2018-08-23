/* Copyright (c) 2018 The Wizzeye Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.wizzeye.app.fragments;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.StringRes;
import android.support.design.widget.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import java.util.Random;
import java.util.regex.Pattern;

import app.wizzeye.app.R;
import app.wizzeye.app.SettingsActivity;
import app.wizzeye.app.call.CallService;

public class RoomSelectionFragment extends BaseFragment implements TextWatcher {

    private static final Pattern ROOM_CHARACTERS = Pattern.compile("^[-_a-z0-9]*$");

    private EditText mRoom;
    private TextInputLayout mRoomLayout;
    private Button mJoin;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_room_selection, container, false);
        mRoom = view.findViewById(R.id.room);
        mRoomLayout = view.findViewById(R.id.room_layout);
        mJoin = view.findViewById(R.id.join);

        mRoom.addTextChangedListener(this);
        mRoom.setText(generateRandomRoom());

        mJoin.setOnClickListener(v -> joinRoom());

        return view;
    }

    private String generateRandomRoom() {
        String[] colors = getResources().getStringArray(R.array.random_colors);
        String[] animals = getResources().getStringArray(R.array.random_animals);
        Random random = new Random();
        return colors[random.nextInt(colors.length)] + "-" + animals[random.nextInt(animals.length)];
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    @Override
    public void afterTextChanged(Editable s) {
        String name = s.toString();

        // Clean up room name
        name = name.toLowerCase();
        name = name.replaceAll("\\s", "-");
        if (!name.equals(s.toString())) {
            s.replace(0, s.length(), name);
            return;
        }

        // Validate room name
        @StringRes int error = 0;
        if (!name.isEmpty()) {
            if (!ROOM_CHARACTERS.matcher(name).matches()) {
                error = R.string.roomselection_error_invalid;
            } else if (name.length() < 5) {
                error = R.string.roomselection_error_short;
            } else if (name.length() > 64) {
                error = R.string.roomselection_error_long;
            }
        }

        // Apply
        if (error != 0) {
            mRoomLayout.setError(getString(error));
        } else {
            mRoomLayout.setErrorEnabled(false);
        }
        mJoin.setEnabled(error == 0 && !name.isEmpty());
    }

    private void joinRoom() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        Uri server = Uri.parse(prefs.getString(SettingsActivity.KEY_SERVER, getString(R.string.pref_server_default)));
        getMainActivity().startService(new Intent(getMainActivity(), CallService.class)
            .setData(Uri.withAppendedPath(server, Uri.encode(mRoom.getText().toString()))));
    }

}
