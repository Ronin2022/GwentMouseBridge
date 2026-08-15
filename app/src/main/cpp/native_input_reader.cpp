#include <jni.h>

#include <cerrno>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {

jint negative_errno() {
    return static_cast<jint>(-(errno == 0 ? EIO : errno));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_ronin_gwentmousebridge_NativeInputReader_openDevice(
        JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return -EINVAL;
    const char* raw_path = env->GetStringUTFChars(path, nullptr);
    if (raw_path == nullptr) return -ENOMEM;
    const int fd = open(raw_path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    env->ReleaseStringUTFChars(path, raw_path);
    return fd >= 0 ? fd : negative_errno();
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_ronin_gwentmousebridge_NativeInputReader_readEvent(
        JNIEnv* env, jclass, jint fd, jintArray output, jint timeout_ms) {
    if (fd < 0 || output == nullptr || env->GetArrayLength(output) < 3) return -EINVAL;

    pollfd descriptor{};
    descriptor.fd = fd;
    descriptor.events = POLLIN;
    int poll_result;
    do {
        poll_result = poll(&descriptor, 1, timeout_ms);
    } while (poll_result < 0 && errno == EINTR);
    if (poll_result == 0) return 0;
    if (poll_result < 0) return negative_errno();
    if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) return -ENODEV;
    if ((descriptor.revents & POLLIN) == 0) return 0;

    input_event event{};
    const ssize_t bytes = read(fd, &event, sizeof(event));
    if (bytes < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
    if (bytes < 0) return negative_errno();
    if (bytes != static_cast<ssize_t>(sizeof(event))) return -EIO;

    const jint values[] = {
            static_cast<jint>(event.type),
            static_cast<jint>(event.code),
            static_cast<jint>(event.value),
    };
    env->SetIntArrayRegion(output, 0, 3, values);
    return env->ExceptionCheck() ? -EFAULT : 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_ronin_gwentmousebridge_NativeInputReader_setExclusive(
        JNIEnv*, jclass, jint fd, jboolean enabled) {
    if (fd < 0) return -EBADF;
    int grab = enabled == JNI_TRUE ? 1 : 0;
    return ioctl(fd, EVIOCGRAB, grab) == 0 ? 0 : negative_errno();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_ronin_gwentmousebridge_NativeInputReader_closeDevice(
        JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
}
