package com.afwsamples.testdpc.comp;

interface IDeviceOwnerService {
    /**
     * Notify device owner that work profile is unlocked.
     */
    oneway void notifyUserIsUnlocked(in UserHandle callingUserHandle);

    /**
     * Request device owner to switch to owner user.
     */
    oneway void switchToOwner();
}
