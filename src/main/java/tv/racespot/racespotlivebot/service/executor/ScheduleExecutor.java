/**
 * Copyright (C) 2021 by Amobee Inc.
 * All Rights Reserved.
 */
package tv.racespot.racespotlivebot.service.executor;

import static tv.racespot.racespotlivebot.util.MessageUtil.hasAdminPermission;
import static tv.racespot.racespotlivebot.util.MessageUtil.notifyChecked;
import static tv.racespot.racespotlivebot.util.MessageUtil.notifyFailed;
import static tv.racespot.racespotlivebot.util.MessageUtil.sendStackTraceToChannel;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import de.btobastian.sdcf4j.Command;
import de.btobastian.sdcf4j.CommandExecutor;
import tv.racespot.racespotlivebot.data.ScheduledEvent;
import tv.racespot.racespotlivebot.data.ScheduledEventRepository;
import tv.racespot.racespotlivebot.data.SeriesLogo;
import tv.racespot.racespotlivebot.data.SeriesLogoRepository;
import tv.racespot.racespotlivebot.data.UserMapping;
import tv.racespot.racespotlivebot.data.UserMappingRepository;
import tv.racespot.racespotlivebot.service.rest.SheetsManager;

import org.apache.commons.lang3.StringUtils;
import org.javacord.api.DiscordApi;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.event.message.reaction.ReactionAddEvent;
import org.javacord.api.listener.message.reaction.ReactionAddListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScheduleExecutor implements CommandExecutor {

    private final String RACESPOT_DISCORD_IMAGE =
        "https://images-ext-2.discordapp.net/external/1VFV1ZRDAahXbuMLichmZRhPSe2qhyhtvgI0zxwTyl4/https/yt3.ggpht.com/ytc/AAUvwnjVGjj07oMFkJ6fnpkO-ac8h2895p49cDK17i9_Pw%3Ds88-c-k-c0x00ffffff-no-rj";

    private final String scheduleChannelId;
    private final String adminChannelId;
    private final String talentChannelId;
    private final String errorChannelId;

    private final Logger logger;

    private final SheetsManager sheetsManager;

    private final ScheduledEventRepository scheduleRepository;
    private final UserMappingRepository userRepository;
    private final SeriesLogoRepository seriesLogoRepository;

    private DiscordApi api;

    public ScheduleExecutor(
        final DiscordApi api,
        final SheetsManager sheetsManager,
        final ScheduledEventRepository scheduledEventRepository,
        final UserMappingRepository userMappingRepository,
        final SeriesLogoRepository seriesLogoRepository,
        final String scheduleChannelId,
        final String adminChannelId,
        final String talentChannelId,
        final String errorChannelId) {
        this.api = api;
        this.sheetsManager = sheetsManager;
        this.scheduleRepository = scheduledEventRepository;
        this.userRepository = userMappingRepository;
        this.seriesLogoRepository = seriesLogoRepository;
        this.scheduleChannelId = scheduleChannelId;
        this.adminChannelId = adminChannelId;
        this.talentChannelId = talentChannelId;
        this.errorChannelId = errorChannelId;

        this.logger = LoggerFactory.getLogger(EventExecutor.class);
    }

    @Command(aliases = "!clearSchedule")
    public void clearSchedule(Message message, Server server, User user, TextChannel inputChannel) {
        if (!hasAdminPermission(server, user) || !inputChannel.getIdAsString()
            .equalsIgnoreCase(adminChannelId)) {
            return;
        }
        try {
            List<ScheduledEvent> events = scheduleRepository.findAll();
            long[] messageIds =
                events.stream().map(ScheduledEvent::getdMessageId).mapToLong(i -> i).toArray();
            List<ReactionAddListener> listeners = api.getReactionAddListeners();
            for (ReactionAddListener listener : listeners) {
                api.removeListener(listener);
            }
            api.getTextChannelById(scheduleChannelId).get().deleteMessages(messageIds).join();
            scheduleRepository.deleteAll(events);
            notifyChecked(message);
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            ex.printStackTrace();
            ServerTextChannel
                channel = api.getServerTextChannelById(errorChannelId).get();
            new MessageBuilder()
                .append(String.format("Error while clearing schedule: %s", ex.getMessage()))
                .send(inputChannel);
            sendStackTraceToChannel(
                "Error when clearing schedule",
                channel,
                ex);
            notifyFailed(message);
        }
    }

    @Command(aliases = "!updateSchedule")
    public void updateSchedule(Message message, Server server, User user, TextChannel inputChannel) {
        if (!hasAdminPermission(server, user) || !inputChannel.getIdAsString()
            .equalsIgnoreCase(adminChannelId)) {
            return;
        }

        try {
            ServerTextChannel scheduleChannel = server.getTextChannelById(scheduleChannelId).get();
            List<ScheduledEvent> events = sheetsManager.getWeeklyEvents();
            for (ScheduledEvent singleEvent : events) {
                logger.info(String.format("Checking %s", singleEvent.getSeriesName()));
                ScheduledEvent existingEvent = scheduleRepository.findByIndex(singleEvent.getIndex());

                if(existingEvent == null) {
                    ServerTextChannel
                            channel = api.getServerTextChannelById(errorChannelId).get();
                    channel.sendMessage(String.format("Saved Event for %s cannot be found. Please clear and reimport" +
                            "schedule.", singleEvent.getSeriesName()));
                }

                if (hasTalentChanged(existingEvent, singleEvent)) {
                    logger.info(String.format("%s has had talent updates", existingEvent.getSeriesName()));
                    List<UserMapping> users = getUserMappingsForEvent(singleEvent);
                    updateTalent(existingEvent, singleEvent, users, server);

                    logger.info(String.format("Editing message with id %d", existingEvent.getdMessageId()));
                    Message scheduleMessage = scheduleChannel.getMessageById(existingEvent.getdMessageId()).join();
                    String tagMessage = getMentionStringFromMappings(server, users);
                    scheduleMessage.edit(tagMessage, constructScheduleEmbed(existingEvent));

                    scheduleRepository.save(existingEvent);
                }
            }
            notifyChecked(message);
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            ex.printStackTrace();
            ServerTextChannel
                channel = api.getServerTextChannelById(errorChannelId).get();
            new MessageBuilder()
                .append(String.format("Error while updating schedule: %s", ex.getMessage()))
                .send(inputChannel);
            sendStackTraceToChannel(
                "Error when updating schedule",
                channel,
                ex);
            notifyFailed(message);
        }
    }

    private List<String> getTalentNotificationMessages(
        final ScheduledEvent existingEvent,
        final ScheduledEvent singleEvent,
        final Server server,
        final List<UserMapping> users) {
        List<String> messages = new ArrayList<>();
        if (users.size() == 0) {
            return messages;
        }
        if(!existingEvent.getProducer().equalsIgnoreCase(singleEvent.getProducer())) {
            Optional<UserMapping> mapping = users.stream().filter((m) -> m.getTalentName()
                .equalsIgnoreCase(singleEvent.getProducer())).findFirst();

            if(mapping.isPresent()) {
                Optional<User> userOptional = server.getMemberById(mapping.get().getdUserId());
                userOptional.ifPresent(user -> messages.add(getUpdateMessageForTalent("Producer", singleEvent, user)));;
            }
        }
        if(!existingEvent.getLeadCommentator().equalsIgnoreCase(singleEvent.getLeadCommentator())) {
            Optional<UserMapping> mapping = users.stream().filter((m) -> m.getTalentName()
                .equalsIgnoreCase(singleEvent.getLeadCommentator())).findFirst();

            if(mapping.isPresent()) {
                Optional<User> userOptional = server.getMemberById(mapping.get().getdUserId());
                userOptional.ifPresent(user -> messages.add(getUpdateMessageForTalent("Lead Commentator", singleEvent, user)));;
            }
        }

        if(StringUtils.isNotEmpty(singleEvent.getColourOne()) && !singleEvent.getColourOne().equalsIgnoreCase(existingEvent.getColourOne())) {
            Optional<UserMapping> mapping = users.stream().filter((m) -> m.getTalentName()
                .equalsIgnoreCase(singleEvent.getColourOne())).findFirst();

            if(mapping.isPresent()) {
                Optional<User> userOptional = server.getMemberById(mapping.get().getdUserId());
                userOptional.ifPresent(user -> messages.add(getUpdateMessageForTalent("Color Commentator", singleEvent, user)));;
            }
        }

        if(StringUtils.isNotEmpty(singleEvent.getColourTwo()) && !singleEvent.getColourTwo().equalsIgnoreCase(existingEvent.getColourTwo())) {
            Optional<UserMapping> mapping = users.stream().filter((m) -> m.getTalentName()
                .equalsIgnoreCase(singleEvent.getColourTwo())).findFirst();

            if(mapping.isPresent()) {
                Optional<User> userOptional = server.getMemberById(mapping.get().getdUserId());
                userOptional.ifPresent(user -> messages.add(getUpdateMessageForTalent("Color Commentator", singleEvent, user)));;
            }
        }

        return messages;
    }

    private String getUpdateMessageForTalent(
        final String talentRole,
        final ScheduledEvent singleEvent,
        User user) {
        return String.format("%s : You have been assigned to %s as a %s", user.getMentionTag(), singleEvent.getSeriesName(), talentRole);
    }

    private void updateTalent(
        final ScheduledEvent existingEvent,
        final ScheduledEvent singleEvent,
        final List<UserMapping> users,
        final Server server) {

        List<String> updateMessages = getTalentNotificationMessages(existingEvent, singleEvent, server, users);

        existingEvent.setProducer(singleEvent.getProducer());
        existingEvent.setLeadCommentator(singleEvent.getLeadCommentator());
        existingEvent.setColourOne(singleEvent.getColourOne());
        existingEvent.setColourTwo(singleEvent.getColourTwo());

        if (updateMessages.size() > 0) {
            ServerTextChannel talentChannel = server.getTextChannelById(talentChannelId).get();
            for(String message : updateMessages) {
                new MessageBuilder()
                    .append(message)
                    .send(talentChannel);
            }
        }

    }

    private boolean hasTalentChanged(final ScheduledEvent existingEvent, final ScheduledEvent singleEvent) {
        return !StringUtils.equals(singleEvent.getProducer(), existingEvent.getProducer())
                || !StringUtils.equals(singleEvent.getLeadCommentator(), existingEvent.getLeadCommentator())
                || !StringUtils.equals(singleEvent.getColourOne(), existingEvent.getColourOne())
                || !StringUtils.equals(singleEvent.getColourTwo(), existingEvent.getColourTwo());
    }

    @Command(aliases = "!postSchedule")
    public void postSchedule(Message message, Server server, User user, TextChannel inputChannel) {
        if (!hasAdminPermission(server, user) || !inputChannel.getIdAsString()
            .equalsIgnoreCase(adminChannelId)) {
            return;
        }

        try {
            ServerTextChannel channel = server.getTextChannelById(scheduleChannelId).get();
            List<ScheduledEvent> events = sheetsManager.getWeeklyEvents();
            for (ScheduledEvent singleEvent : events) {
                List<UserMapping> users = getUserMappingsForEvent(singleEvent);
                String tagMessage = getMentionStringFromMappings(server, users);
                new MessageBuilder()
                    .append(tagMessage)
                    .setEmbed(constructScheduleEmbed(singleEvent))
                    .send(channel)
                    .thenAcceptAsync(sentMessage -> {
                        singleEvent.setdMessageId(sentMessage.getId());
                        scheduleRepository.save(singleEvent);
                        sentMessage.addReactionAddListener(new ReactionAddListener() {
                               @Override
                               public void onReactionAdd(ReactionAddEvent reaction) {
                                   ScheduledEvent event =
                                       scheduleRepository.findBydMessageId(reaction.getMessageId());
                                   UserMapping mapping = userRepository.findBydUserId(reaction.getUserId());
                                   try {
                                       if (isUserOnEvent(event, mapping) && reaction.getEmoji()
                                           .equalsEmoji("\uD83C\uDDFE")) {
                                           // check user is on schedule list, confirm attendance if present
                                           sheetsManager
                                               .updateAttendance(event, true, mapping.getTalentName());
                                           logger.info("yes");
                                       } else if (isUserOnEvent(event, mapping) && reaction.getEmoji()
                                           .equalsEmoji("\uD83C\uDDF3")) {
                                           // check if user is on schedule list, mark down absent if present
                                           sheetsManager
                                               .updateAttendance(event, false, mapping.getTalentName());
                                           logger.info("no");
                                       } else if (reaction.getEmoji().equalsEmoji("\uD83C\uDD93")) {
                                           // add to free list
                                           logger.info("free");
                                       }
                                   } catch (Exception ex) {
                                       logger.info(ex.getMessage());
                                       ex.printStackTrace();
                                   }
                               }
                           }
                        ).removeAfter(5, TimeUnit.DAYS);
                    });
            }
            notifyChecked(message);
        } catch (Exception ex) {
            logger.error(ex.getMessage());
            ex.printStackTrace();
            ServerTextChannel
                channel = api.getServerTextChannelById(errorChannelId).get();
            new MessageBuilder()
                .append(String.format("Error while posting schedule: %s", ex.getMessage()))
                .send(inputChannel);
            sendStackTraceToChannel(
                "Error when posting schedule",
                channel,
                ex);
            notifyFailed(message);
        }
    }

    private boolean isUserOnEvent(ScheduledEvent event, UserMapping mapping) {
        return mapping.getTalentName().equalsIgnoreCase(event.getProducer())
            || mapping.getTalentName().equalsIgnoreCase(event.getLeadCommentator())
            || mapping.getTalentName().equalsIgnoreCase(event.getColourOne())
            || mapping.getTalentName().equalsIgnoreCase(event.getColourTwo());
    }

    private String getMentionStringFromMappings(final Server server, final List<UserMapping> users) {
        StringJoiner joiner = new StringJoiner(", ");
        for (UserMapping mapping : users) {
            Optional<User> userOptional = server.getMemberById(mapping.getdUserId());
            userOptional.ifPresent(user -> joiner.add(user.getMentionTag()));
        }
        if (joiner.length() == 0) {
            return "";
        }
        return joiner.toString();
    }

    private List<UserMapping> getUserMappingsForEvent(final ScheduledEvent event) {
        HashSet<String> talentNames = new HashSet<>();
        talentNames.add(event.getProducer());
        talentNames.add(event.getLeadCommentator());
        if (StringUtils.isNotEmpty(event.getColourOne())) {
            talentNames.add(event.getColourOne());
        }
        if (StringUtils.isNotEmpty(event.getColourTwo())) {
            talentNames.add(event.getColourTwo());
        }
        return userRepository.findByTalentNameIn(talentNames);
    }

    private EmbedBuilder constructScheduleEmbed(ScheduledEvent scheduledEvent) {
        EmbedBuilder builder = new EmbedBuilder()
            .setTitle(String.format("%s \n%s | %s", scheduledEvent.getSeriesName(), scheduledEvent.getDate(), scheduledEvent.getTime()))
            //.setDescription(String.format("%s | %s", scheduledEvent.getDate(), scheduledEvent.getTime()))
            .setColor(new Color(scheduledEvent.getRed(), scheduledEvent.getGreen(), scheduledEvent.getBlue()))
            .setThumbnail(getImageUrl(scheduledEvent))
            .setFooter(scheduledEvent.getStreamLocation(), RACESPOT_DISCORD_IMAGE);

        if (StringUtils.isNotEmpty(scheduledEvent.getDescription())) {
            builder.addInlineField("Details", scheduledEvent.getDescription());
        }

        builder.addInlineField("Producer", StringUtils.isNotEmpty(scheduledEvent.getProducer()) ? scheduledEvent.getProducer() : "TBD")
                .addInlineField("Commentators", getCommentatorString(scheduledEvent));


        if (StringUtils.isNotEmpty(scheduledEvent.getNotes())) {
            builder.addField("Notes", scheduledEvent.getNotes());
        }
        return builder;
    }

    private String getCommentatorString(final ScheduledEvent scheduledEvent) {
        StringJoiner joiner = new StringJoiner(", ");
        joiner.setEmptyValue("TBD");
        if (StringUtils.isNotEmpty(scheduledEvent.getLeadCommentator())) {
            joiner.add(scheduledEvent.getLeadCommentator());
        }
        if (StringUtils.isNotEmpty(scheduledEvent.getColourOne())) {
            joiner.add(scheduledEvent.getColourOne());
        }
        if (StringUtils.isNotEmpty(scheduledEvent.getColourTwo())) {
            joiner.add(scheduledEvent.getColourTwo());
        }
        return joiner.toString();
    }

    private String getImageUrl(ScheduledEvent event) {
        SeriesLogo logo = seriesLogoRepository.findBySeriesNameIgnoreCase(event.getSeriesName());
        if (logo == null) {
            return RACESPOT_DISCORD_IMAGE;
        }
        return logo.getThumbnailUrl();
    }
}