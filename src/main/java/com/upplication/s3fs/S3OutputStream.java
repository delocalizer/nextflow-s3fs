/*
 * Copyright (c) 2013-2015, Centre for Genomic Regulation (CRG).
 * Copyright (c) 2013-2015, Paolo Di Tommaso and the respective authors.
 *
 *   This file is part of 'Nextflow'.
 *
 *   Nextflow is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Nextflow is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with Nextflow.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.upplication.s3fs;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectId;
import com.amazonaws.services.s3.model.StorageClass;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.util.Base64;
import com.upplication.s3fs.util.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.nio.ch.DirectBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.requireNonNull;

/**
 * Parallel S3 multipart uploader. Based on the following code request
 * See https://github.com/Upplication/Amazon-S3-FileSystem-NIO2/pulls
 *
 * @Paolo Di Tommaso
 * @author Tom Wieczorek
 */

public final class S3OutputStream extends OutputStream {

    /**
     * Model a S3 multipart upload request
     */
    static class S3UploadRequest {

        /**
         * ID of the S3 object to store data into.
         */
        private S3ObjectId objectId;

        /**
         * Amazon S3 storage class to apply to the newly created S3 object, if any.
         */
        private StorageClass storageClass;

        /**
         * Metadata that will be attached to the stored S3 object.
         */
        private ObjectMetadata metadata;

        /**
         * Upload chunk max size
         */
        private int chunkSize;

        /**
         * Maximum number of threads allowed
         */
        private int maxThreads;

        /**
         * Maximum number of attempts to upload a chunk in a multiparts upload process
         */
        private int maxAttempts;

        /**
         * Time (milliseconds) to wait after a failed upload to retry a chunk upload
         */
        private long retrySleep;

        /**
         * initialize default values
         */
        {
            retrySleep = 100;
            chunkSize = DEFAULT_CHUNK_SIZE;
            maxAttempts = 5;
            maxThreads = Runtime.getRuntime().availableProcessors();
            if( maxThreads > 1 ) {
                maxThreads--;
            }
        }

        public S3UploadRequest setObjectId(S3ObjectId objectId) {
            this.objectId = objectId;
            return this;
        }

        public S3UploadRequest setStorageClass(StorageClass storageClass) {
            this.storageClass = storageClass;
            return this;
        }

        public S3UploadRequest setStorageClass(String storageClass) {
            if( storageClass==null ) return this;

            try {
                setStorageClass( StorageClass.fromValue(storageClass) );
            }
            catch( IllegalArgumentException e ) {
                log.warn("Not a valid AWS S3 storage class: `{}` -- Using default", storageClass);
            }
            return this;
        }

        
        public S3UploadRequest setStorageEncryption(String storageEncryption) {
            if( storageEncryption == null) {
                return this;
            }
            else if (storageEncryption != "AES256") {
                log.warn("Not a valid S3 server-side encryption type: `{}` -- Currently only AES256 is supported",storageEncryption);
            }
            else {
                ObjectMetadata objectMetadata = new ObjectMetadata();
                objectMetadata.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
                this.setMetadata(objectMetadata);
            }
            return this;
        }

        public S3UploadRequest setMetadata(ObjectMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public S3UploadRequest setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public S3UploadRequest setChunkSize(String chunkSize) {
            if( chunkSize==null ) return this;

            try {
                setChunkSize(Integer.parseInt(chunkSize));
            }
            catch( NumberFormatException e ) {
                log.warn("Not a valid AWS S3 multipart upload chunk size: `{}` -- Using default", chunkSize);
            }
            return this;
        }

        public S3UploadRequest setMaxThreads(int maxThreads) {
            this.maxThreads = maxThreads;
            return this;
        }

        public S3UploadRequest setMaxThreads(String maxThreads) {
            if( maxThreads==null ) return this;

            try {
                setMaxThreads(Integer.parseInt(maxThreads));
            }
            catch( NumberFormatException e ) {
                log.warn("Not a valid AWS S3 multipart upload max threads: `{}` -- Using default", maxThreads);
            }
            return this;
        }

        public S3UploadRequest setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public S3UploadRequest setMaxAttempts(String maxAttempts) {
            if( maxAttempts == null ) return this;
            try {
                this.maxAttempts = Integer.parseInt(maxAttempts);
            }
            catch(NumberFormatException e ) {
                log.warn("Not a valid AWS S3 multipart upload max attempts value: `{}` -- Using default", maxAttempts);
            }
            return this;
        }

        public S3UploadRequest setRetrySleep( long retrySleep ) {
            this.retrySleep = retrySleep;
            return this;
        }

        public S3UploadRequest setRetrySleep( String retrySleep ) {
            if( retrySleep == null ) return this;

            try {
                this.retrySleep = Long.parseLong(retrySleep);
            }
            catch (NumberFormatException e ) {
                log.warn("Not a valid AWS S3 multipart upload retry sleep value: `{}` -- Using default", retrySleep);
            }
            return this;
        }
        
    }

    /**
     * Hack a LinkedBlockingQueue to make the offer method blocking
     *
     * http://stackoverflow.com/a/4522411/395921
     *
     * @param <E>
     */
    static class LimitedQueue<E> extends LinkedBlockingQueue<E>
    {
        public LimitedQueue(int maxSize)
        {
            super(maxSize);
        }

        @Override
        public boolean offer(E e)
        {
            // turn offer() and add() into a blocking calls (unless interrupted)
            try {
                put(e);
                return true;
            } catch(InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(S3OutputStream.class);

    /**
     * Minimum part size of a part in a multipart upload: 5 MiB.
     *
     * @see  <a href="http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadUploadPart.html">Amazon Simple Storage
     *       Service (S3) » API Reference » REST API » Operations on Objects » Upload Part</a>
     */
    private static final int DEFAULT_CHUNK_SIZE = 10 << 20;

    /**
     * Amazon S3 API implementation to use.
     */
    private final AmazonS3 s3;

    /**
     * ID of the S3 object to store data into.
     */
    private final S3ObjectId objectId;

    /**
     * Amazon S3 storage class to apply to the newly created S3 object, if any.
     */
    private final StorageClass storageClass;

    /**
     * Metadata that will be attached to the stored S3 object.
     */
    private final ObjectMetadata metadata;

    /**
     * Indicates if the stream has been closed.
     */
    private volatile boolean closed;

    /**
     * Indicates if the upload has been aborted
     */
    private volatile boolean aborted;

    /**
     * If a multipart upload is in progress, holds the ID for it, {@code null} otherwise.
     */
    private volatile String uploadId;

    /**
     * If a multipart upload is in progress, holds the ETags of the uploaded parts, {@code null} otherwise.
     */
    private Queue<PartETag> partETags;

    /**
     * Holds upload request metadata
     */
    private final S3UploadRequest request;

    /**
     * Instead of allocate a new buffer for each chunks recycle them, putting
     * a buffer instance into this queue when the upload process is completed
     */
    final private Queue<ByteBuffer> bufferPool = new ConcurrentLinkedQueue<ByteBuffer>();

    /**
     * The executor service (thread pool) which manages the upload in background
     */
    private ExecutorService executor;

    /**
     * The current working buffer
     */
    private ByteBuffer buf;

    private MessageDigest md5;

    /**
     * Phaser object to synchronize stream termination
     */
    private Phaser phaser;

    /**
     * Count the number of uploaded chunks
     */
    private int partsCount;


    /**
     * Creates a s3 uploader output stream
     * @param s3 The S3 client
     * @param objectId The S3 object ID to upload
     */
    public S3OutputStream(final AmazonS3 s3, S3ObjectId objectId ) {
        this(s3, new S3UploadRequest().setObjectId(objectId));
    }

    /**
     * Creates a new {@code S3OutputStream} that writes data directly into the S3 object with the given {@code objectId}.
     * No special object metadata or storage class will be attached to the object.
     *
     * @param   s3        Amazon S3 API implementation to use
     * @param   request   An instance of {@link com.upplication.s3fs.S3OutputStream.S3UploadRequest}
     *
     * @throws  NullPointerException  if at least one parameter is {@code null}
     */
    public S3OutputStream(final AmazonS3 s3, S3UploadRequest request) {
        this.s3 = requireNonNull(s3);
        this.objectId = requireNonNull(request.objectId);
        this.metadata = request.metadata != null ? request.metadata : new ObjectMetadata();
        this.storageClass = request.storageClass;
        this.request = request;
        // initialize the buffer
        this.buf = allocate();
        this.md5 = createMd5();
    }


    /**
     * @return A MD5 message digester
     */
    private MessageDigest createMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        }
        catch(NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot find a MD5 algorithm provider",e);
        }
    }


    /**
     * Writes a byte into the uploader buffer. When it is full starts the upload process
     * in a asynchornous manner
     *
     * @param b The byte to be written
     * @throws IOException
     */
    @Override
    public void write (int b) throws IOException {
        if (!buf.hasRemaining()) {
            flush();
        }

        buf.put((byte) b);
        // update the md5 checksum
        md5.update((byte) b);
    }

    /**
     * Flush the current buffer uploading to S3 storage
     *
     * @throws IOException
     */
    @Override
    public void flush() throws IOException {
        // send out the current current
        uploadBuffer(buf);

        // try to reuse a buffer from the poll
        buf = bufferPool.poll();
        if( buf != null ) {
            buf.clear();
        }
        else {
            // allocate a new buffer
            buf = allocate();
        }

        md5 = createMd5();
    }

    /**
     * Create a new byte buffer to hold parallel chunks uploads. Override to use custom
     * buffer capacity or strategy e.g. {@code DirectBuffer}
     *
     * @return The {@code ByteBuffer} instance
     */
    protected ByteBuffer allocate() {
        return ByteBuffer.allocateDirect(request.chunkSize);
    }

    /**
     * Upload the given buffer to S3 storage in a asynchronous manner.
     * NOTE: when the executor service is busy (i.e. there are any more free threads)
     * this method will block
     */
    private void uploadBuffer(ByteBuffer buf) throws IOException {
        // when the buffer is empty nothing to do
        if( buf == null || buf.position()==0 ) { return; }

        if (partsCount == 0) {
            init();
        }

        // set the buffer in read mode and submit for upload
        executor.submit( task(buf, md5.digest(), ++partsCount) );
    }

    /**
     * Initialize multipart upload data structures
     *
     * @throws IOException
     */
    private void init() throws IOException {
        // get the upload id
        uploadId = initiateMultipartUpload().getUploadId();
        if (uploadId == null) {
            throw new IOException("Failed to get a valid multipart upload ID from Amazon S3");
        }
        // create the executor
        executor = createExecutor(request.maxThreads);
        partETags = new LinkedBlockingQueue<>();
        phaser = new Phaser();
        phaser.register();
        log.trace("Starting S3 upload: {}; chunk-size: {}; max-threads: {}", uploadId, request.chunkSize, request.maxThreads);
    }


    /**
     * Creates a {@link Runnable} task to handle the upload process
     * in background
     *
     * @param buffer The buffer to be uploaded
     * @param partIndex The index count
     * @return
     */
    private Runnable task(final ByteBuffer buffer, final byte[] checksum, final int partIndex) {

        phaser.register();
        return new Runnable() {
            @Override
            public void run() {
                try {
                    uploadPart(buffer, checksum, partIndex, false);
                }
                catch (IOException e) {
                    final StringWriter writer = new StringWriter();
                    e.printStackTrace(new PrintWriter(writer));
                    log.error("Upload: {} > Error for part: {}\nCaused by: {}", uploadId, partIndex, writer.toString());
                }
                finally {
                    phaser.arriveAndDeregister();
                }
            }
        };

    }

    /**
     * Close the stream uploading any remaning buffered data
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        if (uploadId == null) {
            putObject(buf, md5.digest());
        }
        else {
            // -- upload remaining chunk
            uploadBuffer(buf);

            // -- shutdown upload executor and await termination
            phaser.arriveAndAwaitAdvance();

            // -- complete upload process
            completeMultipartUpload();

            // -- dispose the buffers
            for( ByteBuffer item : bufferPool ) {
                if( item instanceof DirectBuffer) {
                    ((DirectBuffer) item).cleaner().clean();
                }
            }
        }

        closed = true;
    }

    /**
     * Starts the multipart upload process
     *
     * @return An instance of {@link InitiateMultipartUploadResult}
     * @throws IOException
     */
    private InitiateMultipartUploadResult initiateMultipartUpload() throws IOException {
        final InitiateMultipartUploadRequest request = //
                new InitiateMultipartUploadRequest(objectId.getBucket(), objectId.getKey(), metadata);

        if (storageClass != null) {
            request.setStorageClass(storageClass);
        }

        try {
            return s3.initiateMultipartUpload(request);
        } catch (final AmazonClientException e) {
            throw new IOException("Failed to initiate Amazon S3 multipart upload", e);
        }
    }

    /**
     * Upload the given buffer to the S3 storage using a multipart process
     *
     * @param buf The buffer holding the data to upload
     * @param partNumber The progressive index of this chunk (1-based)
     * @param lastPart {@code true} when it is the last chunk
     * @throws IOException
     */
    private void uploadPart( final ByteBuffer buf, final byte[] checksum, final int partNumber, final boolean lastPart ) throws IOException {
        buf.flip();
        buf.mark();

        int attempt=0;
        boolean success=false;
        try {
            while( !success ) {
                attempt++;
                int len = buf.limit();
                try {
                    log.trace("Uploading part {} with length {} attempt {} for {} ", partNumber, len, attempt, objectId);
                    uploadPart( new ByteBufferInputStream(buf), len, checksum , partNumber, lastPart );
                    success=true;
                }
                catch (AmazonClientException | IOException e) {
                    if( attempt == request.maxAttempts )
                        throw new IOException("Failed to upload multipart data to Amazon S3", e);

                    log.debug("Failed to upload part {} attempt {} for {} -- Caused by: {}", partNumber, attempt, objectId, e.getMessage());
                    sleep(request.retrySleep);
                    buf.reset();
                }
            }
        }
        finally {
            if (!success) {
                closed = true;
                abortMultipartUpload();
            }
            bufferPool.offer(buf);
        }

    }

    private void uploadPart(final InputStream content, final long contentLength, final byte[] checksum, final int partNumber, final boolean lastPart)
            throws IOException {

        if (aborted) return;

        final UploadPartRequest request = new UploadPartRequest();
        request.setBucketName(objectId.getBucket());
        request.setKey(objectId.getKey());
        request.setUploadId(uploadId);
        request.setPartNumber(partNumber);
        request.setPartSize(contentLength);
        request.setInputStream(content);
        request.setLastPart(lastPart);
        request.setMd5Digest(Base64.encodeAsString(checksum));

        final PartETag partETag = s3.uploadPart(request).getPartETag();
        log.trace("Uploaded part {} with length {} for {}: {}", partETag.getPartNumber(), contentLength, objectId, partETag.getETag());
        partETags.add(partETag);

    }

    private void sleep( long millis ) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            log.trace("Sleep was interrupted -- Cause: {}", e.getMessage());
        }
    }

    /**
     * Aborts the multipart upload process
     */
    private synchronized void abortMultipartUpload() {
        if (aborted) return;

        log.debug("Aborting multipart upload {} for {}", uploadId, objectId);
        try {
            s3.abortMultipartUpload(new AbortMultipartUploadRequest(objectId.getBucket(), objectId.getKey(), uploadId));
        }
        catch (final AmazonClientException e) {
            log.warn("Failed to abort multipart upload {}: {}", uploadId, e.getMessage());
        }
        aborted = true;
        phaser.arriveAndDeregister();
    }

    /**
     * Completes the multipart upload process
     * @throws IOException
     */
    private void completeMultipartUpload() throws IOException {
        // if aborted upload just ignore it
        if( aborted ) return;

        final int partCount = partETags.size();
        log.trace("Completing upload to {} consisting of {} parts", objectId, partCount);

        try {
            s3.completeMultipartUpload(new CompleteMultipartUploadRequest( //
                    objectId.getBucket(), objectId.getKey(), uploadId, new ArrayList<>(partETags)));
        } catch (final AmazonClientException e) {
            throw new IOException("Failed to complete Amazon S3 multipart upload", e);
        }

        log.trace("Completed upload to {} consisting of {} parts", objectId, partCount);

        uploadId = null;
        partETags = null;
    }

    /**
     * Stores the given buffer using a single-part upload process
     * @param buf
     * @throws IOException
     */
    private void putObject(ByteBuffer buf, byte[] checksum) throws IOException {
        buf.flip();
        putObject(buf.limit(), new ByteBufferInputStream(buf), checksum);
    }

    /**
     * Stores the given buffer using a single-part upload process
     *
     * @param contentLength
     * @param content
     * @throws IOException
     */
    private void putObject(final long contentLength, final InputStream content, byte[] checksum) throws IOException {

        final ObjectMetadata meta = metadata.clone();
        meta.setContentLength(contentLength);
        meta.setContentMD5( Base64.encodeAsString(checksum) );

        final PutObjectRequest request = new PutObjectRequest(objectId.getBucket(), objectId.getKey(), content, meta);

        if (storageClass != null) {
            request.setStorageClass(storageClass);
        }

        try {
            s3.putObject(request);
        } catch (final AmazonClientException e) {
            throw new IOException("Failed to put data into Amazon S3 object", e);
        }
    }

    /**
     * @return Number of uploaded chunks
     */
    int getPartsCount() {
        return partsCount;
    }


    /** holds a singleton executor instance */
    static private volatile ExecutorService executorSingleton;

    /**
     * Creates a singleton executor instance.
     *
     * @param maxThreads
     *          The max number of allowed threads in the executor pool.
     *          NOTE: changing the size parameter after the first invocation has no effect.
     * @return The executor instance
     */
    private static synchronized ExecutorService createExecutor(int maxThreads) {
        if( executorSingleton == null ) {
            executorSingleton = new ThreadPoolExecutor(maxThreads, maxThreads, 60L, TimeUnit.SECONDS, new LimitedQueue<Runnable>(maxThreads));
            log.trace("Created singleton upload executor -- max-treads: {}", maxThreads);
        }
        return executorSingleton;
    }

    /**
     * Shutdown the executor and clear the singleton
     */
    public static synchronized void shutdownExecutor() {
        log.trace("Uploader shutdown -- Executor: {}", executorSingleton);

        if( executorSingleton != null ) {
            executorSingleton.shutdown();
            log.trace("Uploader await completion");
            awaitExecutorCompletion();
            executorSingleton = null;
            log.trace("Uploader shutdown completed");
        }
    }

    private static void awaitExecutorCompletion() {
        try {
            executorSingleton.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            log.trace("Executor await interrupted -- Cause: {}", e.getMessage());
        }
    }
}
