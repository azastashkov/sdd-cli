package sdd.core.llm;

/**
 * An endpoint's own file store: put an image in it, get an id back, name that id on a user turn.
 *
 * <p>Separate from {@link ChatModel} on purpose. That interface is a single method with dozens of
 * lambda implementations across the test tree, and a second method on it would break every one;
 * more importantly, uploading a file is not completing a chat, and most endpoints cannot do it at
 * all. Ask {@link WireFormat#uploadsAttachments()} before reaching for this.
 *
 * <p>A small hand-written interface is also the test seam this repo uses — there is no Mockito
 * here, the same reason {@code ConfluencePages} and {@code RestClient.Sleeper} exist.
 */
public interface AttachmentStore {

    /**
     * Upload one image and return its id.
     *
     * @param contentType one of the types the store accepts — image/jpeg, image/png, image/tiff or
     *                    image/bmp for GigaChat
     * @throws ModelException if the endpoint has no file store, refuses the upload, or answers
     *                        without an id
     */
    String upload(byte[] image, String filename, String contentType) throws ModelException;

    /**
     * Remove a previously uploaded file. Best effort: the caller already has what it wanted, so a
     * failure here must not fail the caller's work. Not optional to CALL, though — a live store was
     * seen holding 99 files from runs that never cleaned up after themselves.
     */
    void delete(String fileId);
}
