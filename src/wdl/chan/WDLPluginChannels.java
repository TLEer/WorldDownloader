package wdl.chan;

import io.netty.buffer.Unpooled;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.world.chunk.Chunk;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import wdl.EntityUtils;
import wdl.WDL;
import wdl.WDLMessageTypes;
import wdl.WDLMessages;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;

/**
 * Main plugin channel handler. See {@link wdl.chan package-info.java} for more
 * info.
 */
public class WDLPluginChannels {
	private static Logger logger = LogManager.getLogger();
	/**
	 * Packets that have been received.
	 */
	private static HashSet<Integer> receivedPackets = new HashSet<Integer>();
	
	/**
	 * Whether functions that the server is not aware of can be used.
	 * (Packet #0)
	 */
	private static boolean canUseFunctionsUnknownToServer = true;
	/**
	 * Whether all players are allowed to download the world in general.
	 * If false, they aren't allowed, regardless of the other values below.
	 */
	private static boolean canDownloadInGeneral = true;
	/**
	 * The distance from a player that WDL can save chunks.
	 * 
	 * This is only used when {@link #canCacheChunks} is false. 
	 */
	private static int saveRadius = -1;
	/**
	 * Whether a player can cache chunks as they move.  In essence, this means
	 * that if the value is true, the player can download the entire map while
	 * moving about, but if false, the player will only save the nearby chunks
	 * when they stop download.
	 */
	private static boolean canCacheChunks = true;
	/**
	 * Whether or not a player can save entities in the map.
	 */
	private static boolean canSaveEntities = true;
	/**
	 * Whether or not a player can save TileEntities in general.
	 * <br/>
	 * Chests and other containers also require {@link #canSaveContainers}. 
	 */
	private static boolean canSaveTileEntities = true;
	/**
	 * Whether a player can save containers that require opening to save their
	 * contents, such as chests.  For this value to have meaning, the value of
	 * {@link #canSaveTileEntities} must also be true.  
	 */
	private static boolean canSaveContainers = true;
	/**
	 * Map of entity ranges.
	 * 
	 * Key is the entity string, int is the range.
	 */
	private static Map<String, Integer> entityRanges =
			new HashMap<String, Integer>();
	
	/**
	 * Whether players can request permissions.
	 * 
	 * With the default implementation, this is always <i>sent</i> as
	 * <code>true</code>.  However, this needs to be sent for it to be useful -
	 * if the plugin does NOT send it, it does not support permission requests.
	 */
	private static boolean canRequestPermissions = false;
	
	/**
	 * Message to display when requesting.  If empty, nothing
	 * is displayed.
	 */
	private static String requestMessage = "";
	
	/**
	 * Chunk overrides. Any chunk within a range is allowed to be downloaded in.
	 */
	private static Map<String, Multimap<String, ChunkRange>> chunkOverrides = new HashMap<String, Multimap<String, ChunkRange>>();
	
	/**
	 * Active permission requests.
	 */
	private static Map<String, String> requests = new HashMap<String, String>();
	
	/**
	 * Permission request fields that take boolean parameters.
	 */
	public static final List<String> BOOLEAN_REQUEST_FIELDS = Arrays.asList(
			"downloadInGeneral", "cacheChunks", "saveEntities",
			"saveTileEntities", "saveContainers", "getEntityRanges");
	/**
	 * Permission request fields that take integer parameters.
	 */
	public static final List<String> INTEGER_REQUEST_FIELDS = Arrays.asList(
			"saveRadius");
	
	/**
	 * List of new chunk override requests.
	 */
	private static List<ChunkRange> chunkOverrideRequests = new ArrayList<ChunkRange>();
	
	/**
	 * Collection of plugin channels that the server has registered.
	 */
	private static Set<String> registeredChannels = new HashSet<String>();
	
	/**
	 * Checks whether players can use functions unknown to the server.
	 */
	public static boolean canUseFunctionsUnknownToServer() {
		if (receivedPackets.contains(0)) {
			return canUseFunctionsUnknownToServer;
		} else {
			return true;
		}
	}
	
	/**
	 * Checks whether the player should be able to start download at all: Either
	 * {@link #canDownloadInGeneral()} is true, or the player has some chunks
	 * overridden.
	 */
	public static boolean canDownloadAtAll() {
		if (hasChunkOverrides()) {
			return true;
		} else {
			return canDownloadInGeneral();
		}
	}
	
	/**
	 * Checks whether players are allowed to download in general (outside of
	 * overridden chunks).
	 */
	public static boolean canDownloadInGeneral() {
		if (receivedPackets.contains(1)) {
			return canDownloadInGeneral;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Checks if a chunk is within the saveRadius 
	 * (and chunk caching is disabled).
	 */
	public static boolean canSaveChunk(Chunk chunk) {
		if (isChunkOverridden(chunk)) {
			return true;
		}
		
		if (!canDownloadInGeneral()) {
			return false;
		}
		
		if (receivedPackets.contains(1)) {
			if (!canCacheChunks && saveRadius >= 0) {
				int distanceX = chunk.xPosition - WDL.thePlayer.chunkCoordX;
				int distanceZ = chunk.zPosition - WDL.thePlayer.chunkCoordZ;
				
				if (Math.abs(distanceX) > saveRadius ||
						Math.abs(distanceZ) > saveRadius) {
					return false;
				}
			}
			
			return true;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Checks whether entities are allowed to be saved.
	 */
	public static boolean canSaveEntities() {
		if (!canDownloadInGeneral()) {
			return false;
		}
		
		if (receivedPackets.contains(1)) {
			return canSaveEntities;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Checks whether entities are allowed to be saved in the given chunk.
	 */
	public static boolean canSaveEntities(Chunk chunk) {
		if (isChunkOverridden(chunk)) {
			return true;
		}
		
		return canSaveEntities();
	}
	
	/**
	 * Checks whether entities are allowed to be saved in the given chunk.
	 */
	public static boolean canSaveEntities(int chunkX, int chunkZ) {
		if (isChunkOverridden(chunkX, chunkZ)) {
			return true;
		}
		
		return canSaveEntities();
	}
	
	/**
	 * Checks whether a player can save tile entities.
	 */
	public static boolean canSaveTileEntities() {
		if (!canDownloadInGeneral()) {
			return false;
		}
		
		if (receivedPackets.contains(1)) {
			return canSaveTileEntities;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Checks whether a player can save tile entities in the given chunk.
	 */
	public static boolean canSaveTileEntities(Chunk chunk) {
		if (isChunkOverridden(chunk)) {
			return true;
		}
		
		return canSaveTileEntities();
	}
	
	/**
	 * Checks whether a player can save tile entities in the given chunk.
	 */
	public static boolean canSaveTileEntities(int chunkX, int chunkZ) {
		if (isChunkOverridden(chunkX, chunkZ)) {
			return true;
		}
		
		return canSaveTileEntities();
	}
	
	/**
	 * Checks whether containers (such as chests) can be saved.
	 */
	public static boolean canSaveContainers() {
		if (!canDownloadInGeneral()) {
			return false; 
		}
		if (!canSaveTileEntities()) {
			return false;
		}
		if (receivedPackets.contains(1)) {
			return canSaveContainers;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Checks whether containers (such as chests) can be saved.
	 */
	public static boolean canSaveContainers(Chunk chunk) {
		if (isChunkOverridden(chunk)) {
			return true;
		}
		
		return canSaveContainers();
	}
	
	/**
	 * Checks whether containers (such as chests) can be saved.
	 */
	public static boolean canSaveContainers(int chunkX, int chunkZ) {
		if (isChunkOverridden(chunkX, chunkZ)) {
			return true;
		}
		
		return canSaveContainers();
	}
	
	/**
	 * Checks whether maps (the map item, not the world itself) can be saved.
	 */
	public static boolean canSaveMaps() {
		if (!canDownloadInGeneral()) {
			return false; 
		}
		//TODO: Better value than 'canSaveTileEntities'.
		if (receivedPackets.contains(1)) {
			return canSaveTileEntities;
		} else {
			return canUseFunctionsUnknownToServer();
		}
	}
	
	/**
	 * Gets the server-set range for the given entity.
	 * 
	 * @param entity The entity's name (via {@link EntityUtils#getEntityType}).
	 * @return The entity's range, or -1 if no data was recieved.
	 */
	public static int getEntityRange(String entity) {
		if (!canSaveEntities(null)) {
			return -1;
		}
		if (receivedPackets.contains(2)) {
			if (entityRanges.containsKey(entity)) {
				return entityRanges.get(entity);
			} else {
				return -1;
			}
		} else {
			return -1;
		}
	}
	
	/**
	 * Gets the save radius.
	 * 
	 * Note that using {@link #canSaveChunk(Chunk)} is generally better
	 * as it handles most of the radius logic.
	 * 
	 * @return {@link #saveRadius}.
	 */
	public static int getSaveRadius() {
		return saveRadius;
	}
	
	/**
	 * Gets whether chunks can be cached.
	 * 
	 * Note that using {@link #canSaveChunk(Chunk)} is generally better
	 * as it handles most of the radius logic.
	 * 
	 * @return {@link #canCacheChunks}.
	 */
	public static boolean canCacheChunks() {
		return canCacheChunks;
	}
	
	/**
	 * Checks if the server-set entity range is configured.
	 */
	public static boolean hasServerEntityRange() {
		return receivedPackets.contains(2) && entityRanges.size() > 0;
	}
	
	public static Map<String, Integer> getEntityRanges() {
		return new HashMap<String, Integer>(entityRanges);
	}
	
	/**
	 * Gets whether permissions are available.
	 */
	public static boolean hasPermissions() {
		return receivedPackets != null && !receivedPackets.isEmpty();
	}
	
	/**
	 * Gets whether permissions are available.
	 */
	public static boolean canRequestPermissions() {
		return registeredChannels.contains("WDL|REQUEST")
				&& receivedPackets.contains(3) && canRequestPermissions;
	}
	
	/**
	 * Gets the request message.
	 * @return The {@link #requestMessage}.
	 */
	public static String getRequestMessage() {
		if (receivedPackets.contains(3)) {
			return requestMessage;
		} else {
			return null;
		}
	}
	
	/**
	 * Is the given chunk part of a chunk override?
	 */
	public static boolean isChunkOverridden(Chunk chunk) {
		if (chunk == null) {
			return false;
		}
		
		return isChunkOverridden(chunk.xPosition, chunk.zPosition);
	}
	/**
	 * Is the given chunk location part of a chunk override?
	 */
	public static boolean isChunkOverridden(int x, int z) {
		for (Multimap<String, ChunkRange> map : chunkOverrides.values()) {
			for (ChunkRange range : map.values()) {
				if (x >= range.x1 &&
						x <= range.x2 &&
						z >= range.z1 &&
						z <= range.z2) {
					return true;
				}
			}
		}
		
		return false;
	}

	/**
	 * Are there any chunk overrides present?
	 */
	public static boolean hasChunkOverrides() {
		if (!receivedPackets.contains(4)) {
			// XXX It's possible that some implementations may not send
			// packet 4, but still send ranges. If so, that may lead to issues.
			// But right now, I'm not checking that.
			return false;
		}
		if (chunkOverrides == null || chunkOverrides.isEmpty()) {
			return false;
		}
		for (Multimap<String, ChunkRange> m : chunkOverrides.values()) {
			if (!m.isEmpty()) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Gets an immutable copy of the {@link #chunkOverrides} map.
	 */
	public static Map<String, Multimap<String, ChunkRange>> getChunkOverrides() {
		Map<String, Multimap<String, ChunkRange>> returned = new
				HashMap<String, Multimap<String,ChunkRange>>();
		
		for (Map.Entry<String, Multimap<String, ChunkRange>> e : chunkOverrides
				.entrySet()) {
			// Create a copy of the given map.
			Multimap<String, ChunkRange> map = ImmutableMultimap.copyOf(e.getValue());
			
			returned.put(e.getKey(), map);
		}
		
		return ImmutableMap.copyOf(returned);
	}
	
	/**
	 * Create a new permission request.
	 * @param key The key for the request.
	 * @param value The wanted value.
	 */
	public static void addRequest(String key, String value) {
		if (!isValidRequest(key, value)) {
			return;
		}
		
		requests.put(key, value);
	}
	
	/**
	 * Removes the given key from the current permission requests.
	 * 
	 * Does nothing if that value was not already being requested.
	 * 
	 * @param key The key to remove.
	 */
	public static void removeRequest(String key) {
			requests.remove(key);
	}
	
	/**
	 * Gets the requested value for the given key.
	 * 
	 * @param key The key to get.
	 * @return The requested value, or null if there is no such request.
	 */
	public static String getRequest(String key) {
			return requests.get(key);
	}
	
	/**
	 * Gets an immutable copy of the current requests.
	 */
	public static Map<String, String> getRequests() {
		return ImmutableMap.copyOf(requests);
	}
	
	/**
	 * Is the given set of values valid for the given request?
	 * 
	 * Handles checking if the key exists and if the value is valid.
	 * 
	 * @param key The key for the request.
	 * @param value The wanted value.
	 */
	public static boolean isValidRequest(String key, String value) {
		if (key == null || value == null) {
			return false;
		}
		
		if (BOOLEAN_REQUEST_FIELDS.contains(key)) {
			return value.equals("true") || value.equals("false");
		} else if (INTEGER_REQUEST_FIELDS.contains(key)) {
			try {
				Integer.parseInt(value);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		
		return false;
	}
	
	/**
	 * Gets the current list of chunk override requests.
	 */
	public static List<ChunkRange> getChunkOverrideRequests() {
		return ImmutableList.copyOf(chunkOverrideRequests);
	}
	/**
	 * Adds a new chunk override request for the given range.
	 */
	public static void addChunkOverrideRequest(ChunkRange range) {
		chunkOverrideRequests.add(range);
	}
	
	/**
	 * Sends the current requests to the server.
	 */
	public static void sendRequests(String requestReason) {
		if (requests.isEmpty() && chunkOverrideRequests.isEmpty()) {
			return;
		}
		
		ByteArrayDataOutput output = ByteStreams.newDataOutput();
		
		output.writeUTF(requestReason);
		output.writeInt(requests.size());
		for (Map.Entry<String, String> request : requests.entrySet()) {
			output.writeUTF(request.getKey());
			output.writeUTF(request.getValue());
		}
		
		output.writeInt(chunkOverrideRequests.size());
		for (ChunkRange range : chunkOverrideRequests) {
			range.writeToOutput(output);
		}
		
		PacketBuffer requestBuffer = new PacketBuffer(Unpooled.buffer());
		requestBuffer.writeBytes(output.toByteArray());
		C17PacketCustomPayload requestPacket = new C17PacketCustomPayload(
				"WDL|REQUEST", requestBuffer);
		Minecraft.getMinecraft().getNetHandler().addToSendQueue(requestPacket);
	}
	
	/**
	 * Event that is called when the world is loaded. Sets the default values,
	 * and then asks the server to give the correct ones.
	 * 
	 * @param isDifferentServer
	 *            Is the server different from the previous server?
	 */
	public static void onWorldLoad(boolean isDifferentServer) {
		Minecraft minecraft = Minecraft.getMinecraft();
		
		receivedPackets = new HashSet<Integer>();
		requests = new HashMap<String, String>();
		chunkOverrideRequests = new ArrayList<ChunkRange>();
		
		canUseFunctionsUnknownToServer = true;
		
		WDLMessages.chatMessageTranslated(
				WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
				"wdl.messages.permissions.init");
		
		// Register the WDL messages.
		PacketBuffer registerPacketBuffer = new PacketBuffer(Unpooled.buffer());
		// Done like this because "buffer.writeString" doesn't do the propper
		// null-terminated strings.
		registerPacketBuffer.writeBytes(new byte[] {
				'W', 'D', 'L', '|', 'I', 'N', 'I', 'T', '\0',
				'W', 'D', 'L', '|', 'C', 'O', 'N', 'T', 'R', 'O', 'L', '\0',
				'W', 'D', 'L', '|', 'R', 'E', 'Q', 'U', 'E', 'S', 'T', '\0' });
		C17PacketCustomPayload registerPacket = new C17PacketCustomPayload(
				"REGISTER", registerPacketBuffer);
		minecraft.getNetHandler().addToSendQueue(registerPacket);

		// Send the init message.
		C17PacketCustomPayload initPacket;
		try {
			initPacket = new C17PacketCustomPayload("WDL|INIT",
					new PacketBuffer(Unpooled.copiedBuffer(WDL.VERSION
							.getBytes("UTF-8"))));
		} catch (UnsupportedEncodingException e) {
			WDLMessages.chatMessageTranslated(WDLMessageTypes.ERROR,
					"wdl.messages.generalError.noUTF8", e);

			initPacket = new C17PacketCustomPayload("WDL|INIT",
					new PacketBuffer(Unpooled.buffer()));
		}
		minecraft.getNetHandler().addToSendQueue(initPacket);
		
		if (isDifferentServer) {
			registeredChannels.clear();
		}
	}
	
	public static void onPluginChannelPacket(String channel, byte[] bytes) {
		if ("WDL|CONTROL".equals(channel)) {
			ByteArrayDataInput input = ByteStreams.newDataInput(bytes);

			int section = input.readInt();

			receivedPackets.add(section);
			
			switch (section) {
			case 0:
				canUseFunctionsUnknownToServer = input.readBoolean();
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet0",
								canUseFunctionsUnknownToServer);
				
				break;
			case 1: 
				canDownloadInGeneral = input.readBoolean();
				saveRadius = input.readInt();
				canCacheChunks = input.readBoolean();
				canSaveEntities = input.readBoolean();
				canSaveTileEntities = input.readBoolean();
				canSaveContainers = input.readBoolean();

				WDLMessages.chatMessageTranslated(
								WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
								"wdl.messages.permissions.packet1",
								canDownloadInGeneral, saveRadius,
								canCacheChunks, canSaveEntities,
								canSaveTileEntities, canSaveContainers);
				
				//Cancel a download if it is occurring.
				if (!canDownloadInGeneral) {
					if (WDL.downloading) {
						WDLMessages.chatMessageTranslated(
								WDLMessageTypes.ERROR,
								"wdl.messages.generalError.forbidden");
						WDL.cancelDownload();
					}
				}
				break;
			case 2:
				entityRanges.clear();
				
				int count = input.readInt();
				for (int i = 0; i < count; i++) {
					String name = input.readUTF();
					int range = input.readInt();
					
					entityRanges.put(name, range);
				}
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet2",
						entityRanges.size());
				break;
			case 3: 
				canRequestPermissions = input.readBoolean();
				requestMessage = input.readUTF();
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet3",
						canRequestPermissions, requestMessage.length(),
						Integer.toHexString(requestMessage.hashCode()));
				// Don't include the exact message because it's too long and would be spammy.
				break;
			case 4:
				chunkOverrides.clear();
				
				int numRangeGroups = input.readInt();
				int totalRanges = 0;
				for (int i = 0; i < numRangeGroups; i++) {
					String groupName = input.readUTF();
					int groupSize = input.readInt();
					
					Multimap<String, ChunkRange> ranges = HashMultimap
							.<String, ChunkRange> create();
					
					for (int j = 0; j < groupSize; j++) {
						ChunkRange range = ChunkRange.readFromInput(input);
						ranges.put(range.tag, range);
					}
					
					chunkOverrides.put(groupName, ranges);
					
					totalRanges += groupSize;
				}
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet4",
						numRangeGroups, totalRanges);
				break;
			case 5:
				
				String groupToEdit = input.readUTF();
				boolean replaceGroups = input.readBoolean();
				int numNewGroups = input.readInt();
				
				Multimap<String, ChunkRange> newRanges = HashMultimap
						.<String, ChunkRange> create();
				if (!replaceGroups) {
					newRanges.putAll(chunkOverrides.get(groupToEdit));
				}
				
				for (int i = 0; i < numNewGroups; i++) {
					ChunkRange range = ChunkRange.readFromInput(input);
					
					newRanges.put(range.tag, range);
				}
				chunkOverrides.put(groupToEdit, newRanges);
				
				if (replaceGroups) {
					WDLMessages.chatMessageTranslated(
							WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
							"wdl.messages.permissions.packet5.set",
							numNewGroups, groupToEdit);
				} else {
					WDLMessages.chatMessageTranslated(
							WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
							"wdl.messages.permissions.packet5.added",
							numNewGroups, groupToEdit);
				}
				break;
			case 6:
				String groupToChangeTagsFor = input.readUTF();
				int numTags = input.readInt();
				String[] tags = new String[numTags];
				
				for (int i = 0; i < numTags; i++) {
					tags[i] = input.readUTF();
				}
				
				int oldCount = 0;
				for (String tag : tags) {
					oldCount += chunkOverrides.get(groupToChangeTagsFor)
							.get(tag).size();
					chunkOverrides.get(groupToChangeTagsFor).removeAll(tag);
				}
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet6",
						oldCount, groupToChangeTagsFor, Arrays.toString(tags));
				break;
			case 7:
				String groupToSetTagFor = input.readUTF();
				String tag = input.readUTF();
				int numNewRanges = input.readInt();
				
				Collection<ChunkRange> oldRanges = chunkOverrides.get(
						groupToSetTagFor).removeAll(tag);
				int numRangesRemoved = oldRanges.size();
				
				for (int i = 0; i < numNewRanges; i++) {
					//TODO: Ensure that the range has the right tag.
					
					chunkOverrides.get(groupToSetTagFor).put(tag,
							ChunkRange.readFromInput(input));
				}
				
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.packet7",
						numRangesRemoved, groupToSetTagFor, tag, numNewRanges);
				break;
			default:
				WDLMessages.chatMessageTranslated(
						WDLMessageTypes.PLUGIN_CHANNEL_MESSAGE,
						"wdl.messages.permissions.unknownPacket", section);
				
				StringBuilder messageBuilder = new StringBuilder();
				for (byte b : bytes) {
					messageBuilder.append(b).append(' ');
				}

				logger.info(messageBuilder.toString());
			}
		} else if ("REGISTER".equals(channel)) {
			try {
				String channelsString = new String(bytes, "UTF-8");
				List<String> channels = Arrays.asList(channelsString.split("\0"));
				registeredChannels.addAll(channels);
			} catch (UnsupportedEncodingException e) {
				WDLMessages.chatMessageTranslated(WDLMessageTypes.ERROR,
						"wdl.messages.generalError.noUTF8", e);
			}
		} else if ("UNREGISTER".equals(channel)) {
			try {
				String channelsString = new String(bytes, "UTF-8");
				List<String> channels = Arrays.asList(channelsString.split("\0"));
				registeredChannels.removeAll(channels);
			} catch (UnsupportedEncodingException e) {
				WDLMessages.chatMessageTranslated(WDLMessageTypes.ERROR,
						"wdl.messages.generalError.noUTF8", e);
			}
		}
	}
	
	/**
	 * A range of chunks.
	 */
	public static class ChunkRange {
		public ChunkRange(String tag, int x1, int z1, int x2, int z2) {
			this.tag = tag;
			
			// Ensure that the order is correct
			if (x1 > x2) {
				this.x1 = x2;
				this.x2 = x1;
			} else {
				this.x1 = x1;
				this.x2 = x2;
			}
			if (z1 > z2) {
				this.z1 = z2;
				this.z2 = z1;
			} else {
				this.z1 = z1;
				this.z2 = z2;
			}
		}
		
		/**
		 * The tag of this chunk range.
		 */
		public final String tag;
		/**
		 * Range of coordinates.  x1 will never be higher than x2, as will z1
		 * with z2.
		 */
		public final int x1, z1, x2, z2;
		
		/**
		 * Reads and creates a new ChunkRange from the given
		 * {@link ByteArrayDataInput}.
		 */
		public static ChunkRange readFromInput(ByteArrayDataInput input) {
			String tag = input.readUTF();
			int x1 = input.readInt();
			int z1 = input.readInt();
			int x2 = input.readInt();
			int z2 = input.readInt();
			
			return new ChunkRange(tag, x1, z1, x2, z2);
		}
		
		/**
		 * Writes this ChunkRange to the given {@link ByteArrayDataOutput}.
		 * 
		 * Note that I expect most serverside implementations will ignore the
		 * tag, but it still is included for clarity.  The value in it can be
		 * anything so long as it is not null - an empty string will do.
		 */
		public void writeToOutput(ByteArrayDataOutput output) {
			output.writeUTF(this.tag);
			
			output.writeInt(this.x1);
			output.writeInt(this.z1);
			output.writeInt(this.x2);
			output.writeInt(this.z2);
		}

		@Override
		public String toString() {
			return "ChunkRange [tag=" + tag + ", x1=" + x1 + ", z1=" + z1
					+ ", x2=" + x2 + ", z2=" + z2 + "]";
		}
	}
}