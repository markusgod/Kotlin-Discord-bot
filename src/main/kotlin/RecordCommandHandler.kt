import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import java.time.LocalDateTime
import java.util.*
import kotlin.concurrent.schedule

class RecordCommandHandler : ListenerAdapter()
{
    private var isRecording = mutableMapOf<Long, Boolean>()
    private var recorders = mutableMapOf<Long, AudioRecorder>()
    private var stopTask = mutableMapOf<Long, TimerTask?>()

    override fun onGuildMessageReceived(event: GuildMessageReceivedEvent) {
        val guild = event.guild
        val guildIdLong = guild.idLong
        val guildMemberId = event.author.id
        val message = event.message
        val textChannel : MessageChannel = message.textChannel
        if (!(guild.getMemberById(guildMemberId)?.voiceState ?: return).inVoiceChannel()) return
        if (!isRecording.getOrPut(guildIdLong){false} && message.contentRaw.startsWith("::record"))
            startRecord(guildIdLong, guild, event, guildMemberId, message, textChannel)
        else if (isRecording.getOrPut(guildIdLong){false} && message.contentRaw =="::stop")
            stopTask[guild.idLong]?.run()
    }

    private fun startRecord(guildIdLong: Long, guild: Guild, event: GuildMessageReceivedEvent, guildMemberId: String, message: Message, textChannel: MessageChannel) {
        var sendTextChannel = textChannel
        var scheduleTime = 300000L
        stopTask[guildIdLong]?.cancel()
        isRecording[guildIdLong] = true
        try {
            guild.audioManager.receivingHandler = recorders.getOrPut(guildIdLong) { AudioRecorder() }
        } catch (e: OutOfMemoryError) {
            println(e.toString() + e.message)
            event.message.textChannel.sendMessage("Server is out of memory, try again later").queue()
            guild.audioManager.receivingHandler = null
            return
        }
        val recordChannel = (guild.getMemberById(guildMemberId)?.voiceState ?: return).channel
        try {
            guild.audioManager.openAudioConnection(recordChannel)
        } catch (e: PermissionException) {
            guild.audioManager.receivingHandler = null
            return
        }
        scheduleTime = tryGetRecordTime(message, scheduleTime, event)
        if (event.message.mentionedMembers.isNotEmpty()) {
            val user = event.message.mentionedMembers.first().user
            user.openPrivateChannel().queue { sendTextChannel = it }
        }
        scheduleStop(sendTextChannel, guild, scheduleTime)
        val hms = toHumanTime(scheduleTime)
        event.channel.sendMessage("Starting a $hms long recording. Use `::stop` to stop record earlier.").queue()
    }

    private fun tryGetRecordTime(message: Message, scheduleTime: Long, event: GuildMessageReceivedEvent): Long {
        var resultTime = scheduleTime
        val splitMessage = message.contentRaw.split(" ")
        if (splitMessage.size > 1 && splitMessage[1].toLongOrNull() != null) {
            val newScheduleTime = splitMessage[1].toLong() * 1000
            if (newScheduleTime < recordLengthLimit)
                resultTime = newScheduleTime
            else
                event.channel.sendMessage("Requested record is longer than current limit (${toHumanTime(recordLengthLimit)}).").queue()
        }
        return resultTime
    }

    private fun stopRecord(messageChannel: MessageChannel, guild : Guild)
    {
        isRecording[guild.idLong] = false
        guild.audioManager.closeAudioConnection()
        val filename = LocalDateTime.now().toString() + guild.id
        val record = (recorders[guild.idLong] ?: return).endRecord(filename)
        try {
            messageChannel.sendFile(record).submit().thenRunAsync { record.delete() }
        }catch (e : PermissionException)
        {
            println(e.toString() + e.message)
            e.message?.let { messageChannel.sendMessage(it).queue() }
            guild.audioManager.receivingHandler = null
            return
        }
        stopTask[guild.idLong]?.cancel()
    }

    private fun scheduleStop(messageChannel: MessageChannel, guild: Guild, time : Long)
    {
        stopTask[guild.idLong] = Timer("Limit records", false).schedule(time) {
            stopRecord(messageChannel, guild)
        }
    }
}