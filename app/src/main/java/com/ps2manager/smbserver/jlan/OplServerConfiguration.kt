/* Adapted from SimbaDroid's JLANFileServerConfiguration
 * (https://github.com/buttercookie42/SimbaDroid), used under MPL-2.0.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package com.ps2manager.smbserver.jlan

import android.os.Build
import org.filesys.debug.DebugConfigSection
import org.filesys.server.SrvSession
import org.filesys.server.auth.ClientInfo
import org.filesys.server.auth.ISMBAuthenticator
import org.filesys.server.auth.LocalAuthenticator
import org.filesys.server.auth.UserAccountList
import org.filesys.server.auth.acl.DefaultAccessControlManager
import org.filesys.server.config.CoreServerConfigSection
import org.filesys.server.config.GlobalConfigSection
import org.filesys.server.config.InvalidConfigurationException
import org.filesys.server.config.SecurityConfigSection
import org.filesys.server.config.ServerConfiguration
import org.filesys.server.core.DeviceContextException
import org.filesys.server.filesys.DiskDeviceContext
import org.filesys.server.filesys.DiskInterface
import org.filesys.server.filesys.DiskSharedDevice
import org.filesys.server.filesys.FilesystemsConfigSection
import org.filesys.smb.server.SMBConfigSection
import org.filesys.smb.server.SMBSrvSession
import org.filesys.ftp.FTPConfigSection
import org.springframework.extensions.config.element.GenericConfigElement
import java.io.File
import java.net.InetAddress
import java.util.EnumSet

class OplServerConfiguration(
    hostName: String,
    port: Int,
    shareName: String,
    sharePath: String,
    workgroup: String,
    ftpPort: Int = -1
) : ServerConfiguration(hostName) {

    init {
        // Debug — "Debug" level logs every SMB packet/file op, which is a large amount of
        // synchronous console I/O per request. That's fine for troubleshooting but it throttles
        // real gameplay throughput and, combined with the file tee in SmbServerService, competes
        // with game-data I/O on the same storage device. Keep it to errors only.
        val debugConfig = DebugConfigSection(this)
        val debugConfigElement = GenericConfigElement("output")
        val logLevelConfigElement = GenericConfigElement("logLevel")
        logLevelConfigElement.setValue("Error")
        debugConfig.setDebug("org.filesys.debug.ConsoleDebug", debugConfigElement)

        // Core
        val coreConfig = CoreServerConfigSection(this)
        // Bigger headroom on the larger buffer tiers (used for bulk file reads during ISO
        // streaming) so requests don't have to block waiting for a buffer to free up.
        coreConfig.setMemoryPool(
            intArrayOf(256, 4096, 16384, 66000),
            intArrayOf(20, 20, 10, 10),
            intArrayOf(100, 100, 100, 100)
        )
        // A fixed pool of exactly 6 threads (min == max) gives the server no elasticity: once
        // all 6 are busy (e.g. serving reads for the ISO plus ART/VMC lookups concurrently),
        // further requests queue and gameplay stalls. Give it room to grow under load.
        coreConfig.setThreadPool(8, 24)
        coreConfig.getThreadPool().setDebug(false)

        // Global
        GlobalConfigSection(this)

        // Security
        val secConfig = SecurityConfigSection(this)
        val accessControlManager = DefaultAccessControlManager()
        accessControlManager.setDebug(false)
        accessControlManager.initialize(this, GenericConfigElement("aclManager"))
        secConfig.setAccessControlManager(accessControlManager)
        secConfig.setUserAccounts(UserAccountList())

        // Share — mirrors your existing OPL folder layout
        val filesysConfig = FilesystemsConfigSection(this)
        val diskInterface: DiskInterface = OplDiskDriver()
        val shareDir = File(sharePath)
        listOf("CD", "DVD", "ART", "THM", "VMC", "POPS").forEach { File(shareDir, it).mkdirs() }
        addShare(diskInterface, this, filesysConfig, secConfig, shareName, shareDir.absolutePath)

        // SMB
        val smbConfig = SMBConfigSection(this)
        smbConfig.setServerName(hostName)
        smbConfig.setDomainName(workgroup)
        smbConfig.setTcpipSMB(true)
        smbConfig.setTcpipSMBPort(port)

        val authenticator = object : LocalAuthenticator() {
            override fun authenticateUser(
                client: ClientInfo, sess: SrvSession<*>, alg: ISMBAuthenticator.PasswordAlgorithm
            ): ISMBAuthenticator.AuthStatus = ISMBAuthenticator.AuthStatus.AUTHENTICATED
        }
        authenticator.setDebug(false)
        authenticator.setAllowGuest(true)
        authenticator.setAccessMode(ISMBAuthenticator.AuthMode.USER)
        authenticator.initialize(this, GenericConfigElement("authenticator"))
        smbConfig.setAuthenticator(authenticator)
        smbConfig.setNetBIOSDebug(false)
        smbConfig.setTcpipSMB(true)
        smbConfig.setTcpipSMBPort(port)
        smbConfig.setNetBIOSSMB(false)   // <-- add this line — we don't need legacy NetBIOS at all
        smbConfig.setHostAnnounceDebug(false)
        smbConfig.setSessionDebugFlags(EnumSet.noneOf(SMBSrvSession.Dbg::class.java))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            smbConfig.setDisableNIOCode(true)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            smbConfig.setDisableHashedOpenFileMap(true)
        }

        // FTP — reuses the same share defined above via the shared FilesystemsConfigSection.
        // ftpPort <= 0 means "don't start the FTP server".
        if (ftpPort > 0) {
            val ftpConfig = FTPConfigSection(this)
            ftpConfig.setFTPPort(ftpPort)
            ftpConfig.setAllowAnonymousFTP(true)
            ftpConfig.setAnonymousFTPAccount("anonymous")
        }
    }

    @Throws(InvalidConfigurationException::class)
    fun setBindAddress(address: InetAddress) {
        val smbConfig = getConfigSection(SMBConfigSection.SectionName) as SMBConfigSection
        smbConfig.setSMBBindAddress(address)

        val ftpConfig = getConfigSection(FTPConfigSection.SectionName) as? FTPConfigSection
        ftpConfig?.setFTPBindAddress(address)
    }

    companion object {
        @Throws(DeviceContextException::class)
        private fun addShare(
            diskInterface: DiskInterface,
            serverConfig: ServerConfiguration,
            filesysConfig: FilesystemsConfigSection,
            secConfig: SecurityConfigSection,
            shareName: String,
            sharePath: String
        ) {
            val driverConfig = GenericConfigElement("driver")
            val localPathConfig = GenericConfigElement("LocalPath")
            localPathConfig.setValue(sharePath)
            driverConfig.addChild(localPathConfig)
            driverConfig.addChild(GenericConfigElement("DiskIsCaseInsensitive"))

            val diskDeviceContext = diskInterface.createContext(shareName, driverConfig) as DiskDeviceContext
            diskDeviceContext.setShareName(shareName)
            diskDeviceContext.setConfigurationParameters(driverConfig)
            diskDeviceContext.enableChangeHandler(false)

            val diskDev = DiskSharedDevice(shareName, diskInterface, diskDeviceContext)
            diskDev.setConfiguration(serverConfig)
            diskDev.setAccessControlList(secConfig.getGlobalAccessControls())
            diskDeviceContext.startFilesystem(diskDev)
            filesysConfig.addShare(diskDev)
        }
    }
}
