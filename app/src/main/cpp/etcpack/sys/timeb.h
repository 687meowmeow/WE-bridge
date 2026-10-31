/*
 * bionic compatibility shim for etcpack.
 *
 * Android's bionic libc removed <sys/timeb.h> (the ftime() API was retired
 * from POSIX). etcpack.cxx includes it for the CLI timing helpers
 * (compressFile's "It took N milliseconds" printout) — functions that are
 * never called from the library paths we use. This shim provides the
 * minimal struct + ftime() so the vendored etcpack source stays 100%
 * unmodified.
 */
#ifndef _WEPWAWET_SYS_TIMEB_SHIM_H
#define _WEPWAWET_SYS_TIMEB_SHIM_H

#include <sys/time.h>
#include <time.h>

struct timeb {
    time_t       time;      /* seconds since epoch */
    unsigned short millitm; /* milliseconds */
    short        timezone;  /* local timezone offset (minutes west of UTC) */
    short        dstflag;   /* DST, always 0 here */
};

static inline void ftime(struct timeb *t) {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    t->time = tv.tv_sec;
    t->millitm = (unsigned short)(tv.tv_usec / 1000);
    t->timezone = 0;
    t->dstflag = 0;
}

#endif /* _WEPWAWET_SYS_TIMEB_SHIM_H */
