package trace2code.auto;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Lossless bounded ordinal spool. Candidate: bounded sequential compression output buffers. */
public final class TraceEventSpool {
    private static final boolean COMPACT_STORAGE = true;
    private final IndexedSpool spool;
    public TraceEventSpool(String base) throws IOException { spool = new IndexedSpool(base); }
    public void append(long ordinal, String json) throws IOException { spool.append(ordinal,json); }
    public void finish(Path output) throws IOException { spool.finish(output); spool.cleanup(); }
    private static BufferedOutputStream observerOutput(Path output) throws IOException {
        Path packed=Paths.get(output.toString()+".gz");
        GZIPOutputStream zip=new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(packed.toFile()),8388608),65536) {
            { def.setLevel(1); }
        };
        return new BufferedOutputStream(zip,65536);
    }
    private static final class IndexedSpool {
        private static final int CELL = 12, PAGE_CELLS = 4096, PAGE_BYTES = CELL * PAGE_CELLS;
        private final Path dataPath, indexPath;
        private final OutputStream data;
        private final BlockData blocks;
        private final RandomAccessFile index;
        private final LinkedHashMap<Long, byte[]> pages = new LinkedHashMap<Long, byte[]>(16, .75f, true);
        private long position, highest = -1L, count;
        private boolean closed;
        IndexedSpool(String base) throws IOException {
            dataPath=Paths.get(base + ".spool.data");indexPath=Paths.get(base + ".spool.index");
            Path parent=dataPath.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
            if(Files.exists(dataPath)||Files.exists(indexPath))throw new IOException("refuse to overwrite an existing spool");
            blocks=COMPACT_STORAGE?new BlockData(dataPath):null;
            data=blocks==null?new BufferedOutputStream(new FileOutputStream(dataPath.toFile()),65536):blocks;
            index=new RandomAccessFile(indexPath.toFile(),"rw");
        }
        private byte[] page(long pageId) throws IOException {
            Long key=Long.valueOf(pageId);byte[] p=pages.get(key);if(p!=null)return p;
            if(pages.size()>=128) flushPages(64);
            p=new byte[PAGE_BYTES];long start=pageId*PAGE_BYTES;
            if(start<index.length()) {index.seek(start);index.readFully(p,0,(int)Math.min(PAGE_BYTES,index.length()-start));}
            pages.put(key,p);return p;
        }
        private void flushPages(int count) throws IOException {
            ArrayList<Long> keys=new ArrayList<Long>();
            for(Long key:pages.keySet()){keys.add(key);if(keys.size()==count)break;}
            Collections.sort(keys);
            byte[] joined=new byte[Math.min(128,keys.size())*PAGE_BYTES];
            int used=0;long first=-1,previous=-2;
            for(Long key:keys){
                if(used>0&&key.longValue()!=previous+1){index.seek(first*PAGE_BYTES);index.write(joined,0,used);used=0;}
                if(used==0)first=key.longValue();
                System.arraycopy(pages.get(key),0,joined,used,PAGE_BYTES);used+=PAGE_BYTES;previous=key.longValue();
            }
            if(used>0){index.seek(first*PAGE_BYTES);index.write(joined,0,used);}
            for(Long key:keys)pages.remove(key);
        }
        synchronized void append(long key,String json) throws IOException {
            if(closed||key<0)throw new IOException("invalid/closed spool");
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
            byte[] p=page(key/PAGE_CELLS);int cell=(int)(key%PAGE_CELLS)*CELL;
            if(readInt(p,cell+8)!=0)throw new IOException("duplicate event key " + key);
            data.write(bytes);putLong(p,cell,position);putInt(p,cell+8,bytes.length);
            position+=bytes.length;highest=Math.max(highest,key);count++;
        }
        synchronized void finish(Path output) throws IOException {
            if(!closed) {
                data.close();
                flushPages(pages.size());
                pages.clear();index.close();closed=true;
            }
            RandomAccessFile rows=new RandomAccessFile(dataPath.toFile(),"r");
            RandomAccessFile cells=new RandomAccessFile(indexPath.toFile(),"r");
            BlockReader compressed=blocks==null?null:new BlockReader(blocks);
            BufferedOutputStream writer=observerOutput(output);
            byte[] p=new byte[PAGE_BYTES],row=new byte[8192],newline=System.lineSeparator().getBytes(StandardCharsets.UTF_8);
            long emitted=0;
            try {
                for(long first=0;first<=highest;first+=PAGE_CELLS) {
                    cells.seek((first/PAGE_CELLS)*PAGE_BYTES);cells.readFully(p);
                    for(int i=0;i<PAGE_CELLS&&first+i<=highest;i++) {
                        int c=i*CELL,n=readInt(p,c+8);if(n==0)continue;
                        long offset=readLong(p,c);if(offset<0||offset+n>position)throw new IOException("invalid spool index");
                        if(row.length<n)row=new byte[n];
                        if(compressed==null) {
                            if(rows.getFilePointer()!=offset)rows.seek(offset);rows.readFully(row,0,n);
                        } else compressed.read(offset,row,n);
                        writer.write(row,0,n);writer.write(newline);emitted++;
                    }
                }
                if(emitted!=count)throw new IOException("spool coverage mismatch");
            } finally {try{writer.close();}finally{try{rows.close();}finally{try{cells.close();}finally{if(compressed!=null)compressed.close();}}}}
        }
        void cleanup() throws IOException {
            // Only after ALL three outputs and metadata succeeded. An earlier
            // successful channel remains available if a later channel fails.
            Files.delete(dataPath);Files.delete(indexPath);
            if(blocks!=null)Files.delete(blocks.directoryPath);
        }
        private static void putInt(byte[] p,int o,int x) {for(int i=3;i>=0;i--){p[o+i]=(byte)x;x>>>=8;}}
        private static int readInt(byte[] p,int o) {int x=0;for(int i=0;i<4;i++)x=(x<<8)|(p[o+i]&255);return x;}
        private static void putLong(byte[] p,int o,long x) {for(int i=7;i>=0;i--){p[o+i]=(byte)x;x>>>=8;}}
        private static long readLong(byte[] p,int o) {long x=0;for(int i=0;i<8;i++)x=(x<<8)|(p[o+i]&255);return x;}
    }

    /** Bounded, independently checksummed zlib blocks. Ordinal cells continue
     * to refer to logical byte offsets; no row or field is omitted. */
    private static final class BlockData extends OutputStream {
        private static final int BLOCK=262144, DIRECTORY_CELL=24;
        private final Path dataPath,directoryPath;
        private final OutputStream output,directory;
        private long physical;
        private final byte[] raw=new byte[BLOCK],packed=new byte[BLOCK*2+1024];
        private final Deflater codec=new Deflater(1);
        private int used; private long logical,blockCount;
        private boolean closed;
        BlockData(Path path) throws IOException {
            dataPath=path;directoryPath=Paths.get(path.toString()+".blocks");
            if(Files.exists(directoryPath))throw new IOException("existing block directory");
            output=new BufferedOutputStream(new FileOutputStream(path.toFile()),8388608);
            directory=new BufferedOutputStream(new FileOutputStream(directoryPath.toFile()),65536);
        }
        @Override public void write(int b) throws IOException { raw[used++]=(byte)b;if(used==BLOCK)emit(); }
        @Override public void write(byte[] b,int off,int n) throws IOException {
            if(closed)throw new IOException("closed block store");
            while(n>0){int take=Math.min(n,BLOCK-used);System.arraycopy(b,off,raw,used,take);
                used+=take;off+=take;n-=take;if(used==BLOCK)emit();}
        }
        private void emit() throws IOException {
            if(used==0)return;
            codec.reset();codec.setInput(raw,0,used);codec.finish();int length=0;
            while(!codec.finished()){
                int n=codec.deflate(packed,length,packed.length-length);
                if(n==0||length+n>=packed.length)throw new IOException("block codec stalled");length+=n;
            }
            byte[] cell=new byte[DIRECTORY_CELL];
            IndexedSpool.putLong(cell,0,logical);IndexedSpool.putLong(cell,8,physical);
            IndexedSpool.putInt(cell,16,used);IndexedSpool.putInt(cell,20,length);
            directory.write(cell);output.write(packed,0,length);physical+=length;
            logical+=used;blockCount++;used=0;
        }
        @Override public void close() throws IOException {
            if(closed)return;emit();output.close();directory.close();codec.end();closed=true;
        }
    }
    private static final class BlockReader {
        private final RandomAccessFile data,directory;
        private final long logicalSize,blockCount;
        private final Inflater codec=new Inflater();
        private final byte[] packed=new byte[BlockData.BLOCK*2+1024];
        private final LinkedHashMap<Long,byte[]> cache=new LinkedHashMap<Long,byte[]>(4,.75f,true);
        BlockReader(BlockData blocks) throws IOException {
            data=new RandomAccessFile(blocks.dataPath.toFile(),"r");
            directory=new RandomAccessFile(blocks.directoryPath.toFile(),"r");
            logicalSize=blocks.logical;blockCount=blocks.blockCount;
            if(directory.length()!=blockCount*BlockData.DIRECTORY_CELL)throw new IOException("block directory length mismatch");
        }
        private byte[] block(long id) throws IOException {
            Long key=Long.valueOf(id);byte[] bytes=cache.get(key);if(bytes!=null)return bytes;
            byte[] cell=new byte[BlockData.DIRECTORY_CELL];
            directory.seek(id*BlockData.DIRECTORY_CELL);directory.readFully(cell);
            long logical=IndexedSpool.readLong(cell,0),offset=IndexedSpool.readLong(cell,8);
            int raw=IndexedSpool.readInt(cell,16),length=IndexedSpool.readInt(cell,20);
            if(id<0||id>=blockCount||logical!=id*BlockData.BLOCK||raw<=0||raw>BlockData.BLOCK
               ||length<=0||length>packed.length||offset<0||offset+length>data.length())throw new IOException("invalid compressed block");
            data.seek(offset);data.readFully(packed,0,length);codec.reset();codec.setInput(packed,0,length);
            bytes=new byte[raw];int done=0;
            try {
                while(!codec.finished()&&done<raw){int n=codec.inflate(bytes,done,raw-done);
                    if(n==0)break;done+=n;}
            } catch(DataFormatException error){throw new IOException("block checksum/format failure",error);}
            if(done!=raw||!codec.finished()||codec.getRemaining()!=0)throw new IOException("compressed block coverage mismatch");
            if(cache.size()>=4)cache.remove(cache.entrySet().iterator().next().getKey());cache.put(key,bytes);return bytes;
        }
        void read(long offset,byte[] target,int n) throws IOException {
            if(offset<0||offset+n>logicalSize)throw new IOException("invalid logical span");int written=0;
            while(written<n){long id=offset/BlockData.BLOCK;int at=(int)(offset%BlockData.BLOCK);
                byte[] bytes=block(id);int take=Math.min(n-written,bytes.length-at);
                if(take<=0)throw new IOException("invalid block span");System.arraycopy(bytes,at,target,written,take);
                offset+=take;written+=take;}
        }
        void close() throws IOException {codec.end();try{data.close();}finally{directory.close();}}
    }


}







