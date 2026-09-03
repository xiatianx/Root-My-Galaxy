#include <signal.h>
#include <unistd.h>
#include <sys/stat.h>

int main(int argc, char *argv[], char *envp[]) {
    sigset_t empty;
    sigemptyset(&empty);
    sigprocmask(SIG_SETMASK, &empty, NULL);

    const char *target = "/system/bin/true";
    char *argv_new[] = { "true", NULL };
    execve(target, (char* const*)argv_new, envp);
    _exit(1);
}