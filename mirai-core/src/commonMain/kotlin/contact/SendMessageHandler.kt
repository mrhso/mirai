/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.internal.contact

import net.mamoe.mirai.contact.*
import net.mamoe.mirai.event.nextEventOrNull
import net.mamoe.mirai.internal.MiraiImpl
import net.mamoe.mirai.internal.asQQAndroidBot
import net.mamoe.mirai.internal.forwardMessage
import net.mamoe.mirai.internal.longMessage
import net.mamoe.mirai.internal.message.*
import net.mamoe.mirai.internal.network.Packet
import net.mamoe.mirai.internal.network.QQAndroidClient
import net.mamoe.mirai.internal.network.protocol.data.proto.MsgComm
import net.mamoe.mirai.internal.network.protocol.packet.OutgoingPacket
import net.mamoe.mirai.internal.network.protocol.packet.chat.MusicSharePacket
import net.mamoe.mirai.internal.network.protocol.packet.chat.image.ImgStore
import net.mamoe.mirai.internal.network.protocol.packet.chat.receive.*
import net.mamoe.mirai.internal.network.protocol.packet.chat.receive.createToFriend
import net.mamoe.mirai.internal.network.protocol.packet.chat.receive.createToGroup
import net.mamoe.mirai.message.MessageReceipt
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.castOrNull
import net.mamoe.mirai.utils.currentTimeSeconds
import java.lang.UnsupportedOperationException

/**
 * 通用处理器
 */
internal abstract class SendMessageHandler<C : Contact> {
    abstract val contact: C
    abstract val senderName: String

    val messageSourceKind: MessageSourceKind
        get() {
            return when (contact) {
                is Group -> MessageSourceKind.GROUP
                is Friend -> MessageSourceKind.FRIEND
                is Member -> MessageSourceKind.TEMP
                is Stranger -> MessageSourceKind.STRANGER
                else -> error("Unsupported contact: $contact")
            }
        }

    val bot get() = contact.bot.asQQAndroidBot()

    val targetUserUin: Long? get() = contact.castOrNull<User>()?.uin
    val targetGroupUin: Long? get() = contact.castOrNull<Group>()?.uin
    val targetGroupCode: Long? get() = contact.castOrNull<Group>()?.groupCode

    val targetOtherClientBotUin: Long? get() = contact.castOrNull<OtherClient>()?.bot?.id

    val targetUin: Long get() = targetGroupUin ?: targetOtherClientBotUin ?: contact.id

    val groupInfo: MsgComm.GroupInfo?
        get() = if (isToGroup) MsgComm.GroupInfo(
            groupCode = targetGroupCode!!,
            groupCard = senderName // Cinnamon
        ) else null

    val isToGroup: Boolean get() = contact is Group

    suspend fun MessageChain.convertToLongMessageIfNeeded(
        step: SendMessageStep,
    ): MessageChain {
        suspend fun sendLongImpl(): MessageChain {
            val resId = uploadLongMessageHighway(this)
            return this + RichMessage.longMessage(
                brief = takeContent(27),
                resId = resId,
                timeSeconds = currentTimeSeconds()
            ) // LongMessageInternal replaces all contents and preserves metadata
        }
        return when (step) {
            SendMessageStep.FIRST -> {
                // 只需要在第一次发送的时候验证长度
                // 后续重试直接跳过
                if (contains(ForceAsLongMessage)) {
                    return sendLongImpl()
                }

                if (!contains(IgnoreLengthCheck)) {
                    verityLength(this, contact)
                }

                this
            }
            SendMessageStep.LONG_MESSAGE -> {
                if (contains(DontAsLongMessage)) this // fragmented
                else sendLongImpl()
            }
            SendMessageStep.FRAGMENTED -> this
        }
    }

    /**
     * Final process
     */
    suspend fun sendMessagePacket(
        originalMessage: Message,
        transformedMessage: Message,
        finalMessage: MessageChain,
        step: SendMessageStep,
    ): MessageReceipt<C> {

        val group = contact

        var source: OnlineMessageSource.Outgoing? = null

        bot.network.run {
            sendMessageMultiProtocol(
                bot.client, finalMessage,
                fragmented = step == SendMessageStep.FRAGMENTED
            ) { source = it }.forEach { packet ->

                when (val resp = packet.sendAndExpect<Packet>()) {
                    is MessageSvcPbSendMsg.Response -> {
                        if (resp is MessageSvcPbSendMsg.Response.MessageTooLarge) {
                            return when (step) {
                                SendMessageStep.FIRST -> {
                                    sendMessage(originalMessage, transformedMessage, SendMessageStep.LONG_MESSAGE)
                                }
                                SendMessageStep.LONG_MESSAGE -> {
                                    sendMessage(originalMessage, transformedMessage, SendMessageStep.FRAGMENTED)

                                }
                                else -> {
                                    throw MessageTooLargeException(
                                        group,
                                        originalMessage,
                                        finalMessage,
                                        "Message '${finalMessage.content.take(10)}' is too large."
                                    )
                                }
                            }
                        }
                        check(resp is MessageSvcPbSendMsg.Response.SUCCESS) {
                            "Send group message failed: $resp"
                        }
                    }
                    is MusicSharePacket.Response -> {
                        resp.pkg.checkSuccess("send group music share")

                        source = constructSourceFromMusicShareResponse(finalMessage, resp)
                    }
                }
            }

            check(source != null) {
                "Internal error: source is not initialized"
            }

            try {
                source!!.ensureSequenceIdAvailable()
            } catch (e: Exception) {
                bot.network.logger.warning(
                    "Timeout awaiting sequenceId for group message(${finalMessage.content.take(10)}). Some features may not work properly",
                    e

                )
            }

            return MessageReceipt(source!!, contact)
        }
    }

    private fun sendMessageMultiProtocol(
        client: QQAndroidClient,
        message: MessageChain,
        fragmented: Boolean,
        sourceCallback: (OnlineMessageSource.Outgoing) -> Unit
    ): List<OutgoingPacket> {
        message.takeSingleContent<MusicShare>()?.let { musicShare ->
            return listOf(
                MusicSharePacket(
                    client, musicShare, contact.id,
                    targetKind = if (isToGroup) MessageSourceKind.GROUP else MessageSourceKind.FRIEND // always FRIEND
                )
            )
        }

        return messageSvcSendMessage(client, contact, message, fragmented, sourceCallback)
    }

    abstract val messageSvcSendMessage: (
        client: QQAndroidClient,
        contact: C,
        message: MessageChain,
        fragmented: Boolean,
        sourceCallback: (OnlineMessageSource.Outgoing) -> Unit,
    ) -> List<OutgoingPacket>

    abstract suspend fun constructSourceFromMusicShareResponse(
        finalMessage: MessageChain,
        response: MusicSharePacket.Response
    ): OnlineMessageSource.Outgoing

    open suspend fun uploadLongMessageHighway(
        chain: MessageChain
    ): String = with(contact) {
        return MiraiImpl.uploadMessageHighway(
            bot, this@SendMessageHandler,
            listOf(
                ForwardMessage.Node(
                    senderId = bot.id,
                    time = currentTimeSeconds().toInt(),
                    messageChain = chain,
                    senderName = bot.nick
                )
            ),
            true
        )
    }


    open suspend fun postTransformActions(chain: MessageChain) {

    }
}

/**
 * - [ForwardMessage] -> [ForwardMessageInternal] (by uploading through highway)
 * - ... any others for future
 */
internal suspend fun <C : Contact> SendMessageHandler<C>.transformSpecialMessages(message: Message): MessageChain {
    return message.takeSingleContent<ForwardMessage>()?.let { forward ->
        if (!(message is MessageChain && message.contains(IgnoreLengthCheck))) {
            check(forward.nodeList.size <= 200) {
                throw MessageTooLargeException(
                    contact, forward, forward,
                    "ForwardMessage allows up to 200 nodes, but found ${forward.nodeList.size}"
                )
            }
        }

        val resId = MiraiImpl.uploadMessageHighway(
            bot = contact.bot,
            sendMessageHandler = this,
            message = forward.nodeList,
            isLong = false,
        )
        RichMessage.forwardMessage(
            resId = resId,
            timeSeconds = currentTimeSeconds(),
            forwardMessage = forward,
        )
    }?.toMessageChain() ?: message.toMessageChain()
}

/**
 * Might be recalled with [transformedMessage] `is` [LongMessageInternal] if length estimation failed (sendMessagePacket)
 */
internal suspend fun <C : Contact> SendMessageHandler<C>.sendMessage(
    originalMessage: Message,
    transformedMessage: Message,
    step: SendMessageStep,
): MessageReceipt<C> { // Result cannot be in interface.
    val chain = transformSpecialMessages(transformedMessage)
        .convertToLongMessageIfNeeded(step)

    chain.findIsInstance<QuoteReply>()?.source?.ensureSequenceIdAvailable()

    postTransformActions(chain)

    return sendMessagePacket(originalMessage, transformedMessage, chain, step)
}

internal sealed class UserSendMessageHandler<C : AbstractUser>(
    override val contact: C,
) : SendMessageHandler<C>() {
    override val senderName: String get() = bot.nick

    override suspend fun constructSourceFromMusicShareResponse(
        finalMessage: MessageChain,
        response: MusicSharePacket.Response
    ): OnlineMessageSource.Outgoing {
        throw UnsupportedOperationException("Sending MusicShare to user is not yet supported")
    }
}

internal class FriendSendMessageHandler(
    contact: FriendImpl,
) : UserSendMessageHandler<FriendImpl>(contact) {
    override val messageSvcSendMessage: (client: QQAndroidClient, contact: FriendImpl, message: MessageChain, fragmented: Boolean, sourceCallback: (OnlineMessageSource.Outgoing) -> Unit) -> List<OutgoingPacket> =
        MessageSvcPbSendMsg::createToFriend
}

internal class StrangerSendMessageHandler(
    contact: StrangerImpl,
) : UserSendMessageHandler<StrangerImpl>(contact) {
    override val messageSvcSendMessage: (client: QQAndroidClient, contact: StrangerImpl, message: MessageChain, fragmented: Boolean, sourceCallback: (OnlineMessageSource.Outgoing) -> Unit) -> List<OutgoingPacket> =
        MessageSvcPbSendMsg::createToStranger
}

internal class GroupTempSendMessageHandler(
    contact: NormalMemberImpl,
) : UserSendMessageHandler<NormalMemberImpl>(contact) {
    override val messageSvcSendMessage: (client: QQAndroidClient, contact: NormalMemberImpl, message: MessageChain, fragmented: Boolean, sourceCallback: (OnlineMessageSource.Outgoing) -> Unit) -> List<OutgoingPacket> =
        MessageSvcPbSendMsg::createToTemp
}

internal class GroupSendMessageHandler(
    override val contact: GroupImpl,
) : SendMessageHandler<GroupImpl>() {
    override val messageSvcSendMessage: (client: QQAndroidClient, contact: GroupImpl, message: MessageChain, fragmented: Boolean, sourceCallback: (OnlineMessageSource.Outgoing) -> Unit) -> List<OutgoingPacket> =
        MessageSvcPbSendMsg::createToGroup
    override val senderName: String
        get() = contact.botAsMember.nameCardOrNick

    override suspend fun postTransformActions(chain: MessageChain) {
        chain.asSequence().filterIsInstance<FriendImage>().forEach { image ->
            contact.updateFriendImageForGroupMessage(image)
        }
    }

    override suspend fun constructSourceFromMusicShareResponse(
        finalMessage: MessageChain,
        response: MusicSharePacket.Response
    ): OnlineMessageSource.Outgoing {

        val receipt: OnlinePushPbPushGroupMsg.SendGroupMessageReceipt =
            nextEventOrNull(3000) { it.fromAppId == 3116 }
                ?: OnlinePushPbPushGroupMsg.SendGroupMessageReceipt.EMPTY

        return OnlineMessageSourceToGroupImpl(
            contact,
            internalIds = intArrayOf(receipt.messageRandom),
            providedSequenceIds = intArrayOf(receipt.sequenceId),
            sender = bot,
            target = contact,
            time = currentTimeSeconds().toInt(),
            originalMessage = finalMessage
        )
    }

    companion object {
        /**
         * Ensures server holds the cache
         */
        private suspend fun GroupImpl.updateFriendImageForGroupMessage(image: FriendImage) {
            bot.network.run {
                ImgStore.GroupPicUp(
                    bot.client,
                    uin = bot.id,
                    groupCode = id,
                    md5 = image.md5,
                    size = if (image is OnlineFriendImageImpl) image.delegate.fileLen else 0
                ).sendAndExpect<ImgStore.GroupPicUp.Response>()
            }
        }
    }
}
