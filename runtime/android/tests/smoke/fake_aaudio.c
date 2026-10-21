/* A stand-in for Android's libaaudio.so on the CI runner, which has no audio
 * hardware: implements the AAudio calls Wine's DirectAudio driver uses, and
 * behaves like AAudio in callback mode -- a thread asks the driver for one
 * burst every few milliseconds. What the driver supplies is measured (frames,
 * peak level) and written to $FAKE_AAUDIO_STATS when the stream closes, so the
 * test can check that a Windows program's sound really reached AAudio.
 *
 * Built against bionic with no headers, so every declaration is local. */
typedef unsigned long size_t;
typedef int int32_t;
typedef long int64_t;
typedef unsigned long pthread_t;
struct timespec { long tv_sec; long tv_nsec; };
typedef struct FILE FILE;

extern int pthread_create(pthread_t *, const void *, void *(*)(void *), void *);
extern int pthread_join(pthread_t, void **);
extern int nanosleep(const struct timespec *, struct timespec *);
extern void *calloc(size_t, size_t);
extern void free(void *);
extern char *getenv(const char *);
extern FILE *fopen(const char *, const char *);
extern int fprintf(FILE *, const char *, ...);
extern int fclose(FILE *);

typedef int32_t aaudio_result_t;
typedef int32_t (*data_callback)(void *stream, void *user, void *data, int32_t frames);
typedef void (*error_callback)(void *stream, void *user, aaudio_result_t error);

enum { FORMAT_I16 = 1, FORMAT_FLOAT = 2, BURST = 192 };

struct builder {
    int32_t rate, channels, format, direction, capacity, sharing, perf;
    data_callback callback; void *callback_user;
    error_callback error; void *error_user;
};

static void note(const char *fmt, long a, long b, long c, double d)
{
    const char *path = getenv("FAKE_AAUDIO_STATS");
    FILE *f = path ? fopen(path, "a") : 0;
    if (f) { fprintf(f, fmt, a, b, c, d); fclose(f); }
}

struct stream {
    struct builder b;
    volatile int running;
    pthread_t thread;
    int32_t buffer_size;
    int64_t frames, callbacks;
    double peak;
};

static int32_t frame_bytes(const struct builder *b) { return b->channels * (b->format == FORMAT_I16 ? 2 : 4); }

static void *pump(void *arg)
{
    struct stream *s = arg;
    char *buffer = calloc(BURST, frame_bytes(&s->b));
    struct timespec wait = { 0, (long)BURST * 1000000000L / s->b.rate };
    while (s->running) {
        int32_t i, samples = BURST * s->b.channels;
        if (s->b.callback(s, s->b.callback_user, buffer, BURST) != 0) break;   /* STOP */
        for (i = 0; i < samples; i++) {
            double v = s->b.format == FORMAT_I16 ? ((short *)buffer)[i] / 32768.0 : ((float *)buffer)[i];
            if (v < 0) v = -v;
            if (v > s->peak) s->peak = v;
        }
        s->frames += BURST; s->callbacks++;
        if (s->callbacks % 50 == 0) note("progress frames=%ld callbacks=%ld rate=%ld peak=%.3f\n", s->frames, s->callbacks, s->b.rate, s->peak);
        nanosleep(&wait, 0);
    }
    free(buffer);
    return 0;
}

aaudio_result_t AAudio_createStreamBuilder(struct builder **out)
{
    struct builder *b = calloc(1, sizeof(*b));
    if (!b) return -895; /* AAUDIO_ERROR_NO_MEMORY */
    *out = b;
    return 0;
}
aaudio_result_t AAudioStreamBuilder_delete(struct builder *b) { free(b); return 0; }
void AAudioStreamBuilder_setSampleRate(struct builder *b, int32_t v) { b->rate = v; }
void AAudioStreamBuilder_setChannelCount(struct builder *b, int32_t v) { b->channels = v; }
void AAudioStreamBuilder_setFormat(struct builder *b, int32_t v) { b->format = v; }
void AAudioStreamBuilder_setDirection(struct builder *b, int32_t v) { b->direction = v; }
void AAudioStreamBuilder_setBufferCapacityInFrames(struct builder *b, int32_t v) { b->capacity = v; }
void AAudioStreamBuilder_setSharingMode(struct builder *b, int32_t v) { b->sharing = v; }
void AAudioStreamBuilder_setPerformanceMode(struct builder *b, int32_t v) { b->perf = v; }
void AAudioStreamBuilder_setUsage(struct builder *b, int32_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setInputPreset(struct builder *b, int32_t v) { (void)b; (void)v; }
void AAudioStreamBuilder_setDataCallback(struct builder *b, data_callback cb, void *user) { b->callback = cb; b->callback_user = user; }
void AAudioStreamBuilder_setErrorCallback(struct builder *b, error_callback cb, void *user) { b->error = cb; b->error_user = user; }

aaudio_result_t AAudioStreamBuilder_openStream(struct builder *b, struct stream **out)
{
    struct stream *s = calloc(1, sizeof(*s));
    if (!s) return -895;
    s->b = *b;
    if (!s->b.rate) s->b.rate = 48000;
    if (!s->b.channels) s->b.channels = 2;
    if (!s->b.format) s->b.format = FORMAT_FLOAT;
    if (!s->b.capacity) s->b.capacity = BURST * 16;
    s->buffer_size = BURST * 2;
    note("open direction=%ld rate=%ld channels=%ld\n", s->b.direction, s->b.rate, s->b.channels, 0);
    note("open format=%ld callback=%ld capacity=%ld\n", s->b.format, s->b.callback != 0, s->b.capacity, 0);
    *out = s;
    return 0;
}
aaudio_result_t AAudioStream_requestStart(struct stream *s)
{
    if (s->running) return 0;
    if (!s->b.callback) return -896; /* AAUDIO_ERROR_INVALID_STATE: blocking writes are not emulated */
    note("start direction=%ld\n", s->b.direction, 0, 0, 0);
    s->running = 1;
    return pthread_create(&s->thread, 0, pump, s) ? -899 : 0;
}
aaudio_result_t AAudioStream_requestStop(struct stream *s)
{
    if (!s->running) return 0;
    s->running = 0;
    pthread_join(s->thread, 0);
    return 0;
}
aaudio_result_t AAudioStream_close(struct stream *s)
{
    AAudioStream_requestStop(s);
    note("close direction=%ld frames=%ld callbacks=%ld peak=%.3f\n", s->b.direction, s->frames, s->callbacks, s->peak);
    free(s);
    return 0;
}
int32_t AAudioStream_getSampleRate(struct stream *s) { return s->b.rate; }
int32_t AAudioStream_getChannelCount(struct stream *s) { return s->b.channels; }
int32_t AAudioStream_getFormat(struct stream *s) { return s->b.format; }
int32_t AAudioStream_getFramesPerBurst(struct stream *s) { (void)s; return BURST; }
int32_t AAudioStream_getBufferCapacityInFrames(struct stream *s) { return s->b.capacity; }
int32_t AAudioStream_getBufferSizeInFrames(struct stream *s) { return s->buffer_size; }
aaudio_result_t AAudioStream_setBufferSizeInFrames(struct stream *s, int32_t v)
{
    s->buffer_size = v > s->b.capacity ? s->b.capacity : v;
    return s->buffer_size;
}
int32_t AAudioStream_getSharingMode(struct stream *s) { return s->b.sharing; }
int32_t AAudioStream_getPerformanceMode(struct stream *s) { return s->b.perf; }
int32_t AAudioStream_getXRunCount(struct stream *s) { (void)s; return 0; }
