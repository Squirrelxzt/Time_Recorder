package com.example.timerecorder.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.example.timerecorder.fragment.ActivityRecordFragment;
import com.example.timerecorder.fragment.ScoreFragment;
import com.example.timerecorder.fragment.TimeAxisFragment;

public class ViewPagerAdapter extends FragmentStateAdapter {

    public ViewPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
        super(fragmentActivity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        if (position == 0) {
            return new TimeAxisFragment();
        } else if (position == 1) {
            return new ActivityRecordFragment();
        } else {
            return new ScoreFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
