package ru.bclib.api.dataexchange.handler;

import net.minecraft.network.FriendlyByteBuf;
import ru.bclib.api.dataexchange.DataHandler;
import ru.bclib.api.dataexchange.SyncFileHash;
import ru.bclib.api.dataexchange.handler.DataExchange.SyncFolderDescriptor;
import ru.bclib.api.dataexchange.handler.DataExchange.SyncFolderDescriptor.SubFile;
import ru.bclib.util.Pair;
import ru.bclib.util.Triple;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class AutoFileSyncEntry extends AutoSyncID {
	static class ForDirectFileRequest extends  AutoFileSyncEntry {
		final File relFile;
		
		ForDirectFileRequest(String syncID, File relFile, File absFile) {
			super(AutoSyncID.ForDirectFileRequest.MOD_ID, syncID, absFile, false, (a, b, c) -> false);
			this.relFile = relFile;
		}
		
		@Override
		public int serializeContent(FriendlyByteBuf buf) {
			int res = super.serializeContent(buf);
			DataHandler.writeString(buf, relFile.toString());
			
			return res;
		}
		
		static AutoFileSyncEntry.ForDirectFileRequest finishDeserializeContent(String syncID, FriendlyByteBuf buf){
			final String relFile = DataHandler.readString(buf);
			SyncFolderDescriptor desc = DataExchange.getSyncFolderDescriptor(syncID);
			if (desc!=null) {
				return new AutoFileSyncEntry.ForDirectFileRequest(syncID, new File(relFile), desc.localFolder.resolve(relFile)
																											 .toFile());
			}
			return null;
		}
	}
	public final DataExchange.NeedTransferPredicate needTransfer;
	public final File fileName;
	public final boolean requestContent;
	private SyncFileHash hash;
	
	AutoFileSyncEntry(String modID, File fileName, boolean requestContent, DataExchange.NeedTransferPredicate needTransfer) {
		this(modID, fileName.getName(), fileName, requestContent, needTransfer);
	}
	
	AutoFileSyncEntry(String modID, String uniqueID, File fileName, boolean requestContent, DataExchange.NeedTransferPredicate needTransfer) {
		super(modID, uniqueID);
		this.needTransfer = needTransfer;
		this.fileName = fileName;
		this.requestContent = requestContent;
	}
	
	public SyncFileHash getFileHash() {
		if (hash == null) {
			hash = SyncFileHash.create(modID, fileName, uniqueID);
		}
		return hash;
	}
	
	public byte[] getContent() {
		if (!fileName.exists()) return new byte[0];
		final Path path = fileName.toPath();
		
		try {
			return Files.readAllBytes(path);
		}
		catch (IOException e) {
		
		}
		return new byte[0];
	}
	
	public int serializeContent(FriendlyByteBuf buf) {
		DataHandler.writeString(buf, modID);
		DataHandler.writeString(buf, uniqueID);
		return serializeFileContent(buf);
	}
	
	public static Triple<AutoFileSyncEntry, byte[], AutoSyncID> deserializeContent(FriendlyByteBuf buf) {
		final String modID = DataHandler.readString(buf);
		final String uniqueID = DataHandler.readString(buf);
		byte[] data = deserializeFileContent(buf);
		
		AutoFileSyncEntry entry;
		if (AutoSyncID.ForDirectFileRequest.MOD_ID.equals(modID)){
			entry = AutoFileSyncEntry.ForDirectFileRequest.finishDeserializeContent(uniqueID, buf);
		} else {
			entry = AutoFileSyncEntry.findMatching(modID, uniqueID);
		}
		return new Triple<>(entry, data, new AutoSyncID(modID, uniqueID));
	}
	
	
	public void serialize(FriendlyByteBuf buf) {
		getFileHash().serialize(buf);
		buf.writeBoolean(requestContent);
		
		if (requestContent) {
			serializeFileContent(buf);
		}
	}
	
	public static DataExchange.AutoSyncTriple deserializeAndMatch(FriendlyByteBuf buf) {
		Pair<SyncFileHash, byte[]> e = deserialize(buf);
		AutoFileSyncEntry match = findMatching(e.first);
		return new DataExchange.AutoSyncTriple(e.first, e.second, match);
	}
	
	public static Pair<SyncFileHash, byte[]> deserialize(FriendlyByteBuf buf) {
		SyncFileHash hash = SyncFileHash.deserialize(buf);
		boolean withContent = buf.readBoolean();
		byte[] data = null;
		if (withContent) {
			data = deserializeFileContent(buf);
		}
		
		return new Pair(hash, data);
	}
	
	private int serializeFileContent(FriendlyByteBuf buf) {
		byte[] content = getContent();
		buf.writeInt(content.length);
		buf.writeByteArray(content);
		return content.length;
	}
	
	private static byte[] deserializeFileContent(FriendlyByteBuf buf) {
		byte[] data;
		int size = buf.readInt();
		data = buf.readByteArray(size);
		return data;
	}
	
	
	public static AutoFileSyncEntry findMatching(SyncFileHash hash) {
		return findMatching(hash.modID, hash.uniqueID);
	}
	
	public static AutoFileSyncEntry findMatching(AutoSyncID aid) {
		if (aid instanceof AutoSyncID.ForDirectFileRequest) {
			AutoSyncID.ForDirectFileRequest freq = (AutoSyncID.ForDirectFileRequest) aid;
			SyncFolderDescriptor desc = DataExchange.getSyncFolderDescriptor(freq.uniqueID);
			if (desc != null) {
				SubFile subFile = desc.getLocalSubFile(freq.relFile.toString());
				if (subFile != null) {
					final File absPath = desc.localFolder.resolve(subFile.relPath)
									.toFile();
					return new AutoFileSyncEntry.ForDirectFileRequest(freq.uniqueID, new File(subFile.relPath), absPath);
				}
			}
			return null;
		}
		return findMatching(aid.modID, aid.uniqueID);
	}
	
	public static AutoFileSyncEntry findMatching(String modID, String uniqueID) {
		return DataExchange.getInstance()
						   .getAutoSyncFiles()
						   .stream()
						   .filter(asf -> asf.modID.equals(modID) && asf.uniqueID.equals(uniqueID))
						   .findFirst()
						   .orElse(null);
	}
}
