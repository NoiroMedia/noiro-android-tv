package tv.noiro.player.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real Android per-player preferred output; the OS retains final routing authority. */
public final class PlayerAudioOutput {
    private PlayerAudioOutput() {}
    public static List<AudioDeviceInfo> devices(Context context) {
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(manager==null)return Collections.emptyList();
        try{return Arrays.asList(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS));}
        catch(RuntimeException unavailable){return Collections.emptyList();}
    }
    public static String label(AudioDeviceInfo device) {
        String kind;
        switch(device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:kind="Speaker";break;
            case AudioDeviceInfo.TYPE_HDMI:case AudioDeviceInfo.TYPE_HDMI_ARC:case AudioDeviceInfo.TYPE_HDMI_EARC:kind="HDMI";break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:kind="Bluetooth";break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:case AudioDeviceInfo.TYPE_USB_HEADSET:kind="USB";break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:case AudioDeviceInfo.TYPE_WIRED_HEADSET:kind="Headphones";break;
            default:kind="Audio output";
        }
        String name=String.valueOf(device.getProductName());
        return name.isEmpty()?kind:kind+" · "+name;
    }
}
