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
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Random;
import java.util.regex.Pattern;

import app.wizzeye.app.R;
import app.wizzeye.app.SettingsActivity;
import app.wizzeye.app.service.CallService;

public class RoomSelectionFragment extends BaseFragment implements AdapterView.OnItemClickListener {

    private static final Pattern ROOM_CHARACTERS = Pattern.compile("^[-_a-z0-9]*$");

    private static final String STATE_RANDOM_ROOM = "randomRoom";
    private static final String STATE_CUSTOM_ROOM = "customRoom";

    private SharedPreferences mPreferences;
    private String mRandomRoom;
    private String mCustomRoom;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());

        if (savedInstanceState != null) {
            mRandomRoom = savedInstanceState.getString(STATE_RANDOM_ROOM);
            mCustomRoom = savedInstanceState.getString(STATE_CUSTOM_ROOM);
        }
        if (mRandomRoom == null)
            mRandomRoom = generateRandomRoom();
        if (mCustomRoom == null)
            mCustomRoom = mPreferences.getString(SettingsActivity.KEY_LAST_ROOM, "");

        View view = inflater.inflate(R.layout.fragment_room_selection, container, false);
        ListView list = view.findViewById(R.id.list);
        list.setAdapter(new RoomsAdapter());
        list.setOnItemClickListener(this);
        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_RANDOM_ROOM, mRandomRoom);
        outState.putString(STATE_CUSTOM_ROOM, mCustomRoom);
    }

    private String generateRandomRoom() {
        String[] colors = getResources().getStringArray(R.array.random_colors);
        String[] animals = getResources().getStringArray(R.array.random_animals);
        Random random = new Random();
        return colors[random.nextInt(colors.length)] + "-" + animals[random.nextInt(animals.length)];
    }

    private @StringRes int validateRoomName(String name) {
        if (name.isEmpty()) {
            return 0;
        } else if (!ROOM_CHARACTERS.matcher(name).matches()) {
            return R.string.roomselection_error_invalid;
        } else if (name.length() < 5) {
            return R.string.roomselection_error_short;
        } else if (name.length() > 64) {
            return R.string.roomselection_error_long;
        } else {
            return 0;
        }
    }

    private void joinRoom(String room) {
        if (room.isEmpty() || validateRoomName(room) != 0)
            return;
        Uri server = Uri.parse(mPreferences.getString(SettingsActivity.KEY_SERVER, getString(R.string.pref_server_default)));
        getMainActivity().startService(new Intent(getMainActivity(), CallService.class)
            .setData(Uri.withAppendedPath(server, Uri.encode(room))));
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        switch (RoomSelection.values()[position]) {
        case RANDOM:
            joinRoom(mRandomRoom);
            break;
        case CUSTOM:
            joinRoom(mCustomRoom);
            break;
        }
    }

    private enum RoomSelection {
        RANDOM,
        CUSTOM,
        ;
    }

    private class RoomsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return RoomSelection.values().length;
        }

        @Override
        public RoomSelection getItem(int position) {
            return RoomSelection.values()[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return getCount();
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            final View view;
            if (convertView == null) {
                switch (getItem(position)) {
                case RANDOM:
                    view = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_room_fixed, parent, false);
                    ((TextView) view.findViewById(R.id.room)).setText(mRandomRoom);
                    break;
                case CUSTOM:
                    view = LayoutInflater.from(getContext())
                        .inflate(R.layout.item_room_editable, parent, false);
                    EditText e = view.findViewById(R.id.room);
                    e.addTextChangedListener(new RoomValidator(e));
                    e.setText(mCustomRoom);
                    e.setOnEditorActionListener((v, actionId, event) -> {
                        if (actionId == EditorInfo.IME_ACTION_GO) {
                            joinRoom(mCustomRoom);
                            return true;
                        }
                        return false;
                    });
                    view.findViewById(R.id.label)
                        .setOnClickListener(v -> joinRoom(mCustomRoom));
                    view.findViewById(R.id.chevron)
                        .setOnClickListener(v -> joinRoom(mCustomRoom));
                    break;
                default:
                    view = null;
                }
            } else {
                view = convertView;
            }
            return view;
        }
    }

    private class RoomValidator implements TextWatcher {
        private final EditText mView;

        RoomValidator(EditText view) {
            mView = view;
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
                return; // this method will be called again
            }

            // Validate room name
            @StringRes int error = validateRoomName(name);
            mView.setError(error != 0 ? getString(error) : null);

            // Save room name
            mCustomRoom = name;
        }
    }

}
