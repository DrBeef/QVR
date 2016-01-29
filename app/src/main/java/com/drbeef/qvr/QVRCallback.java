package com.drbeef.qvr;


public interface QVRCallback {

    void SwitchVRMode(int vrMode);
    void BigScreenMode(int mode);
    void SwitchStereoMode(int stereo_mode);
    void Exit();
}
