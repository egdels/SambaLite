package de.schliweb.sambalite.ui.controllers;

/**
 * Interface for providing user feedback across the application.
 * This interface standardizes how controllers show success and error messages,
 * ensuring a consistent user experience throughout the app.
 * <p>
 * The UserFeedbackProvider interface is a key part of the consolidated approach to user feedback
 * in the application. It provides a unified way for controllers to show success, error, and info
 * messages, as well as confirmation and file exists dialogs. This ensures that user feedback is
 * consistent across the application, regardless of which controller is showing the feedback.
 * <p>
 * The primary implementation of this interface is the ProgressController, which uses UIHelper
 * to show Snackbars with Material Design styling. Other controllers like FileOperationsController
 * and DialogController use this interface to show user feedback, falling back to their own
 * implementations for backward compatibility.
 * <p>
 * This approach has several benefits:
 * - Consistent user experience across the application
 * - Centralized control over the appearance and behavior of user feedback
 * - Easier to change the appearance of user feedback in the future
 * - Reduced code duplication
 * - Better separation of concerns
 */
public interface UserFeedbackProvider {
    /**
     * Shows a success message to the user.
     * <p>
     * This method is used to display a success message to the user after an operation
     * has completed successfully. The implementation should use a consistent visual style
     * for success messages across the application.
     * <p>
     * In the default implementation (ProgressController), this method uses UIHelper to show
     * a Snackbar with a green background and the success message.
     *
     * @param message The success message to show to the user
     */
    void showSuccess(String message);

    /**
     * Shows an error message to the user.
     * <p>
     * This method is used to display an error message to the user when an operation
     * has failed. The implementation should use a consistent visual style for error
     * messages across the application.
     * <p>
     * In the default implementation (ProgressController), this method uses UIHelper to show
     * a Snackbar with a red background, the error title, and the error message.
     *
     * @param title   The title of the error, which should be concise and descriptive
     * @param message The detailed error message, which can include technical details
     */
    void showError(String title, String message);

    /**
     * Shows an informational message to the user.
     * <p>
     * This method is used to display an informational message to the user. The implementation
     * should use a consistent visual style for info messages across the application.
     * <p>
     * In the default implementation (ProgressController), this method uses UIHelper to show
     * a Snackbar with a blue background and the info message.
     *
     * @param message The informational message to show to the user
     */
    void showInfo(String message);

    /**
     * Shows a confirmation dialog to the user.
     * <p>
     * This method is used to ask the user to confirm an action before proceeding. The implementation
     * should use a consistent visual style for confirmation dialogs across the application.
     * <p>
     * In the default implementation (ProgressController), this method uses UIHelper to show
     * a dialog with the specified title, message, and buttons for confirming or canceling.
     *
     * @param title     The title of the confirmation dialog
     * @param message   The message explaining what the user is confirming
     * @param onConfirm The action to take when the user confirms the action
     * @param onCancel  The action to take when the user cancels the action
     */
    void showConfirmation(String title, String message, Runnable onConfirm, Runnable onCancel);

    /**
     * Shows a file exists dialog to the user.
     * <p>
     * This method is used when a file operation would overwrite an existing file. It asks
     * the user whether to overwrite the file or cancel the operation. The implementation
     * should use a consistent visual style for file exists dialogs across the application.
     * <p>
     * In the default implementation (ProgressController), this method shows a dialog with
     * the file name and options to overwrite or cancel.
     *
     * @param fileName      The name of the file that already exists
     * @param confirmAction The action to take if the user confirms overwriting the file
     * @param cancelAction  The action to take if the user cancels the operation
     */
    void showFileExistsDialog(String fileName, Runnable confirmAction, Runnable cancelAction);
}