package com.example.timerecorder.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.Fragment;

import com.example.timerecorder.R;
import com.example.timerecorder.view.VerticalTimeAxisView;

public class TimeAxisFragment extends Fragment {

    private VerticalTimeAxisView timeAxisView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_time_axis, container, false);
        timeAxisView = view.findViewById(R.id.time_axis_view);
        return view;
    }

    public VerticalTimeAxisView getTimeAxisView() {
        return timeAxisView;
    }
}
