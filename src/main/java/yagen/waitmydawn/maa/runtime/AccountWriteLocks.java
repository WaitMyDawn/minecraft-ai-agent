package yagen.waitmydawn.maa.runtime;

import java.util.concurrent.locks.ReentrantLock;

/** HTTP 写入与后台偏好更新使用相同条带，避免任务脱离过滤器后发生并发覆盖。 */
public final class AccountWriteLocks {
    private static final ReentrantLock[] LOCKS = new ReentrantLock[256];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new ReentrantLock(); }
    private AccountWriteLocks() {}
    public static ReentrantLock forOwner(long owner) { return LOCKS[Math.floorMod(owner, LOCKS.length)]; }
}
