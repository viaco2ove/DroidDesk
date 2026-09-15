#!/system/bin/sh
# 通过 libtermux-auth.so 设置已知密码 test123
PREFIX=/data/user/0/com.orailnoor.droiddesk/files/usr
export PATH=$PREFIX/bin:/system/bin
export LD_LIBRARY_PATH=$PREFIX/lib
export LD_PRELOAD=$PREFIX/lib/libsocket_hook.so
export TMPDIR=/data/user/0/com.orailnoor.droiddesk/files/tmp

cat > $TMPDIR/set_pw.c << 'EOF'
#include <stdbool.h>
#include <dlfcn.h>
#include <stdio.h>
int main(int argc, char** argv) {
    void* h = dlopen("/data/data/com.orailnoor.droiddesk/files/usr/lib/libtermux-auth.so", 2);
    if (!h) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 2; }
    bool (*change)(const char*) = (bool (*)(const char*)) dlsym(h, "termux_change_passwd");
    if (!change) { fprintf(stderr, "dlsym: %s\n", dlerror()); return 3; }
    int rc = change(argv[1]) ? 0 : 1;
    fprintf(stderr, "termux_change_passwd ok=%d\n", rc == 0);
    return rc;
}
EOF

PREFIX_ABS=/data/data/com.orailnoor.droiddesk/files/usr
$PREFIX/bin/clang -o $TMPDIR/set_pw $TMPDIR/set_pw.c -ldl 2>&1
echo --- run ---
$PREFIX/bin/set_pw test123