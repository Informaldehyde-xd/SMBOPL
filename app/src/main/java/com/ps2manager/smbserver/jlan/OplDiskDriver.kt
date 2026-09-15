/* Adapted from SimbaDroid's SimbaDiskDriver
 * (https://github.com/buttercookie42/SimbaDroid), used under MPL-2.0.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.ps2manager.smbserver.jlan

import android.os.StatFs
import android.util.Log
import org.filesys.server.SrvSession
import org.filesys.server.core.DeviceContextException
import org.filesys.server.filesys.DiskDeviceContext
import org.filesys.server.filesys.DiskSizeInterface
import org.filesys.server.filesys.FileName
import org.filesys.server.filesys.NetworkFile
import org.filesys.server.filesys.SrvDiskInfo
import org.filesys.server.filesys.TreeConnection
import org.filesys.smb.server.disk.JavaNIODeviceContext
import org.filesys.smb.server.disk.JavaNIODiskDriver
import org.springframework.extensions.config.ConfigElement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

class OplDiskDriver : JavaNIODiskDriver(), DiskSizeInterface {
    companion object {
        private const val LOGTAG = "OplDiskDriver"
        private const val BLOCK_SIZE = 512
    }

    override fun getDiskInformation(ctx: DiskDeviceContext, diskDev: SrvDiskInfo) {
        val statFs = StatFs(ctx.getDeviceName())
        diskDev.setBlockSize(BLOCK_SIZE)
        diskDev.setBlocksPerAllocationUnit(statFs.blockSizeLong / BLOCK_SIZE)
        diskDev.setTotalUnits(statFs.blockCountLong)
        diskDev.setFreeUnits(statFs.availableBlocksLong)
    }

    @Throws(IOException::class)
    override fun renameFile(
        sess: SrvSession<*>, tree: TreeConnection, oldName: String, newName: String, netFile: NetworkFile
    ) {
        val context = tree.getContext()
        val oldPath = Paths.get(FileName.buildPath(context.getDeviceName(), oldName, null, java.io.File.separatorChar))
        val newPath = Paths.get(FileName.buildPath(context.getDeviceName(), newName, null, java.io.File.separatorChar))

        val lastMod: FileTime = try {
            Files.getLastModifiedTime(oldPath)
        } catch (ex: IOException) {
            Log.d(LOGTAG, "Couldn't get last modified date, falling back to current time")
            FileTime.fromMillis(System.currentTimeMillis())
        }

        super.renameFile(sess, tree, oldName, newName, netFile)

        Files.setLastModifiedTime(newPath, FileTime.fromMillis(lastMod.toMillis() - 42 * 1000))
        Files.setLastModifiedTime(newPath, lastMod)
    }

    @Throws(DeviceContextException::class)
    override fun createJavaNIODeviceContext(shareName: String, args: ConfigElement): JavaNIODeviceContext {
        return JavaNIODeviceContext(shareName, args)
    }
}
