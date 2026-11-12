package com.commcrete.stardust.audio.v2.framework.remote;

/**
 * The remote microphone, served by RemoteMicService in the consumer's own package (a microphone foreground
 * service) and called from the host app's process, where PTT runs. Both ends are this SDK, in one APK, so
 * the codes below never cross a version boundary — they are still explicit numbers, never enum ordinals.
 */
interface IRemoteMic {

    /**
     * Opens the microphone and returns the read end of a pipe carrying 16-bit little-endian mono PCM at
     * rateHz, or null when it is not recording — see lastFailure(). Returns only once the record is
     * really capturing. inputDeviceId is an AudioDeviceInfo id, or -1 for none. client is linked to death:
     * the capture stops if the caller's process dies. One capture at a time; a second open stops the first.
     */
    ParcelFileDescriptor open(int rateHz, int audioSource, int inputDeviceId, IBinder client);

    /** RemoteMicFailure code of the last refused open, or of the last capture a mute ended; 0 = none. */
    int lastFailure();

    /** Stops the capture; the pipe then reaches end of stream. */
    oneway void close();
}
