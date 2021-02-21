package me.noobgam;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.exporter.PushGateway;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends ListenerAdapter {

    private static final Counter SPEAKING_COUNTER = Counter.build()
            .name("speaking_counter")
            .help("Number of 20ms frames user has been speaking.")
            .labelNames("username")
            .register();

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static volatile PushGateway pg = null;

    public static volatile JDA jda = null;

    private static final Map<String, Boolean> IS_SPEAKING = new ConcurrentHashMap<>();

    public static void main(String[] args) throws LoginException
    {
        if (args.length < 2) {
            System.out.println("You have to provide a token as first argument and prometheus gateway as second");
            System.exit(1);
        }
        pg = new PushGateway(args[1]);
        // args[0] should be the token
        // All other events will be disabled.
        jda = JDABuilder.createLight(args[0], GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
                .addEventListeners(new Main())
                .setMemberCachePolicy(MemberCachePolicy.DEFAULT)
                .enableCache(List.of(CacheFlag.VOICE_STATE))
                .setRawEventsEnabled(true)
                .build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event)
    {
        Message msg = event.getMessage();
        MessageChannel channel = event.getChannel();

        if (msg.getAuthor().isBot()) {
            return;
        }

        String message = msg.getContentRaw();
        if (message.equals("!join")) {
            // Creates a variable equal to the channel that the user is in.
            VoiceChannel connectedChannel = event.getMember().getVoiceState().getChannel();
            // Checks if they are in a channel -- not being in a channel means that the variable = null.
            if(connectedChannel == null) {
                // Don't forget to .queue()!
                channel.sendMessage("You are not connected to a voice channel!").queue();
                return;
            }
            // Gets the audio manager.
            AudioManager audioManager = event.getGuild().getAudioManager();
            // When somebody really needs to chill.
            if(audioManager.isAttemptingToConnect()) {
                return;
            }
            // Connects to the channel.
            var handler = new MetricCollectingHandler();
            audioManager.setReceivingHandler(handler);
            audioManager.openAudioConnection(connectedChannel);
            // Obviously people do not notice someone/something connecting.
        } else if (message.equals("!leave")) { // Checks if the command is !leave.

        }
    }

    public static class MetricCollectingHandler implements AudioReceiveHandler
    {
        @Override
        public boolean canReceiveCombined() {
            return true;
        }

        @Override
        public void handleCombinedAudio(CombinedAudio combinedAudio)
        {

            IS_SPEAKING.entrySet().iterator().forEachRemaining(
                    entry -> entry.setValue(false)
            );
            for (User user : combinedAudio.getUsers()) {
                IS_SPEAKING.put(user.getName(), true);
            }

            for (Map.Entry<String, Boolean> stringBooleanEntry : IS_SPEAKING.entrySet()) {

                if (stringBooleanEntry.getValue()) {
                    SPEAKING_COUNTER.labels(stringBooleanEntry.getKey()).inc();
                }
            }
            try {
                pg.pushAdd(CollectorRegistry.defaultRegistry, "speaking");
            } catch (Exception ex) {
                logger.error("Exception during push gateway add", ex);
            }
        }
    }
}