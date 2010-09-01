/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity;

import com.android.email.Clock;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.email.activity.setup.AccountSetupBasics;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.service.MailService;

import android.app.ActionBar;
import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import java.security.InvalidParameterException;

// TODO Where/when/how do we close loaders??  Do we have to?  Getting this error:
// Finalizing a Cursor that has not been deactivated or closed.
// database = /data/data/com.google.android.email/databases/EmailProvider.db,
// table = Account, query = SELECT _id, displayName, emailAddress FROM Account

/**
 * The main (two-pane) activity for XL devices.
 *
 * TODO Refresh account list when adding/removing/changing(e.g. display name) accounts.
 *      -> Need the MessageList.onResume logic.  Figure out a clean way to do that.
 *
 * TODO Refine "move to".  It also shouldn't work for special messages, like drafts.
 */
public class MessageListXL extends Activity implements View.OnClickListener,
        MessageListXLFragmentManager.TargetActivity, MoveMessageToDialog.Callback {
    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    private static final int LOADER_ID_ACCOUNT_LIST = 0;
    /* package */ static final int MAILBOX_REFRESH_MIN_INTERVAL = 30 * 1000; // in milliseconds
    /* package */ static final int INBOX_AUTO_REFRESH_MIN_INTERVAL = 10 * 1000; // in milliseconds

    private Context mContext;
    private RefreshManager mRefreshManager;
    private final RefreshListener mMailRefreshManagerListener
            = new RefreshListener();

    private View mMessageViewButtonPanel;
    private View mMoveToNewerButton;
    private View mMoveToOlderButton;

    private AccountSelectorAdapter mAccountsSelectorAdapter;
    private final ActionBarNavigationCallback mActionBarNavigationCallback
            = new ActionBarNavigationCallback();

    private MessageOrderManager mOrderManager;

    private final MessageListXLFragmentManager mFragmentManager
            = new MessageListXLFragmentManager(this);

    private final MessageOrderManagerCallback mMessageOrderManagerCallback
            = new MessageOrderManagerCallback();

    private RefreshTask mRefreshTask;

    /**
     * Launch and open account's inbox.
     *
     * @param accountId If -1, default account will be used.
     */
    public static void actionOpenAccount(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId != -1) {
            i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        }
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    /**
     * Launch and open a mailbox.
     *
     * @param accountId must not be -1.
     * @param mailboxId must not be -1.
     */
    public static void actionOpenMailbox(Activity fromActivity, long accountId, long mailboxId) {
        Intent i = new Intent(fromActivity, MessageListXL.class);
        if (accountId == -1 || mailboxId == -1) {
            throw new InvalidParameterException();
        }
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_MAILBOX_ID, Mailbox.QUERY_ALL_INBOXES);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.message_list_xl);

        final boolean isRestoring = (savedInstanceState != null);

        mContext = getApplicationContext();
        mRefreshManager = RefreshManager.getInstance(this);
        mRefreshManager.registerListener(mMailRefreshManagerListener);

        mFragmentManager.setMailboxListFragmentCallback(new MailboxListFragmentCallback());
        mFragmentManager.setMessageListFragmentCallback(new MessageListFragmentCallback());
        mFragmentManager.setMessageViewFragmentCallback(new MessageViewFragmentCallback());

        mMessageViewButtonPanel = findViewById(R.id.message_view_buttons);
        mMoveToNewerButton = findViewById(R.id.moveToNewer);
        mMoveToOlderButton = findViewById(R.id.moveToOlder);
        mMoveToNewerButton.setOnClickListener(this);
        mMoveToOlderButton.setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.unread).setOnClickListener(this);
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.forward).setOnClickListener(this);
        findViewById(R.id.move).setOnClickListener(this);

        mAccountsSelectorAdapter = new AccountSelectorAdapter(mContext, null);

        if (isRestoring) {
            mFragmentManager.loadState(savedInstanceState);
        } else {
            initFromIntent();
        }
        loadAccounts();
    }

    private void initFromIntent() {
        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final long mailboxId = i.getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG,
                    String.format("Welcome: %d %d", accountId, mailboxId));
        }

        if (accountId != -1) {
            mFragmentManager.selectAccount(accountId, mailboxId, true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        mFragmentManager.onSaveInstanceState(outState);
    }

    @Override
    protected void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStart");
        super.onStart();

        mFragmentManager.onStart();

        if (mFragmentManager.isMessageSelected()) {
            updateMessageOrderManager();
        }
    }

    @Override
    protected void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onResume");
        super.onResume();

        MailService.cancelNewMessageNotification(this);
        // TODO Add stuff that's done in MessageList.onResume().
    }

    @Override
    protected void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onPause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onStop");
        super.onStop();

        mFragmentManager.onStop();
        stopMessageOrderManager();
    }

    @Override
    protected void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) Log.d(Email.LOG_TAG, "MessageListXL onDestroy");
        Utility.cancelTaskInterrupt(mRefreshTask);
        mRefreshManager.unregisterListener(mMailRefreshManagerListener);
        super.onDestroy();
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onAttachFragment " + fragment.getClass());
        }
        super.onAttachFragment(fragment);
        mFragmentManager.onAttachFragment(fragment);
    }

    @Override
    public void onBackPressed() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXL onBackPressed");
        }
        if (mFragmentManager.isMessageSelected()) {
            // Go back to the message list.
            // We currently don't use the built-in back mechanism.
            // It'd be nice if we could make use of it, but the semantics of the built-in back is
            // a bit different from how we do it in MessageListXLFragmentManager.
            // Switching to the built-in back will probably require re-writing
            // MessageListXLFragmentManager quite a bit.
            mFragmentManager.goBackToMailbox();
        } else {
            // Perform the default behavior == close the activity.
            super.onBackPressed();
        }
    }


    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.moveToOlder:
                moveToOlder();
                break;
            case R.id.moveToNewer:
                moveToNewer();
                break;
            case R.id.delete:
                onDeleteMessage();
                break;
            case R.id.unread:
                onSetMessageUnread();
                break;
            case R.id.reply:
                MessageCompose.actionReply(this, mFragmentManager.getMessageId(), false);
                break;
            case R.id.reply_all:
                MessageCompose.actionReply(this, mFragmentManager.getMessageId(), true);
                break;
            case R.id.forward:
                MessageCompose.actionForward(this, mFragmentManager.getMessageId());
                break;
            case R.id.move:
                onMoveMessage();
                break;
        }
    }

    private void onDeleteMessage() {
        // the delete triggers mCursorObserver in MessageOrderManager.
        // first move to older/newer before the actual delete
        long messageIdToDelete = mFragmentManager.getMessageId();
        if (!moveToOlder()) moveToNewer();
        ActivityHelper.deleteMessage(this, messageIdToDelete);
        // If this was the last message, moveToOlder/Newer didn't move the current position.
        // MessageOrderManager detects the current message is gone, and we go back to the message
        // list in onMessageNotFound().
    }

    private void onSetMessageUnread() {
        MessageViewFragment f = mFragmentManager.getMessageViewFragment();
        f.onMarkMessageAsRead(false);
        mFragmentManager.goBackToMailbox();
    }

    private void onMoveMessage() {
        long accountId = mFragmentManager.getAccountId();
        long messageId = mFragmentManager.getMessageId();
        MoveMessageToDialog dialog = MoveMessageToDialog.newInstance(this, accountId,
                new long[] {messageId});
        dialog.show(getFragmentManager(), "dialog");
    }

    /**
     * Start {@link MessageOrderManager} if not started, and sync it to the current message.
     */
    private void updateMessageOrderManager() {
        if (!mFragmentManager.isMailboxSelected()) {
            return;
        }
        final long mailboxId = mFragmentManager.getMailboxId();
        if (mOrderManager == null || mOrderManager.getMailboxId() != mailboxId) {
            stopMessageOrderManager();
            mOrderManager = new MessageOrderManager(this, mailboxId, mMessageOrderManagerCallback);
        }
        if (mFragmentManager.isMessageSelected()) {
            mOrderManager.moveTo(mFragmentManager.getMessageId());
        }
    }

    private class MessageOrderManagerCallback implements MessageOrderManager.Callback {
        @Override
        public void onMessagesChanged() {
            updateNavigationArrows();
        }

        @Override
        public void onMessageNotFound() {
            // Current message gone.
            mFragmentManager.goBackToMailbox();
        }
    }

    /**
     * Stop {@link MessageOrderManager}.
     */
    private void stopMessageOrderManager() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
    }

    /**
     * Called when the default account is not found, i.e. there's no account set up.
     */
    private void onNoAccountFound() {
        // Open Welcome, which in turn shows the adding a new account screen.
        Welcome.actionStart(this);
        finish();
        return;
    }

    /**
     * Disable/enable the previous/next buttons for the message view.
     */
    private void updateNavigationArrows() {
        mMoveToNewerButton.setEnabled((mOrderManager != null) && mOrderManager.canMoveToNewer());
        mMoveToOlderButton.setEnabled((mOrderManager != null) && mOrderManager.canMoveToOlder());
    }

    private boolean moveToOlder() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToOlder()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (mFragmentManager.isMessageSelected() && (mOrderManager != null)
                && mOrderManager.moveToNewer()) {
            mFragmentManager.selectMessage(mOrderManager.getCurrentMessageId());
            return true;
        }
        return false;
    }

    private class MailboxListFragmentCallback implements MailboxListFragment.Callback {
        @Override
        public void onMailboxSelected(long accountId, long mailboxId) {
            mFragmentManager.selectMailbox(mailboxId, true);
        }
    }

    private class MessageListFragmentCallback implements MessageListFragment.Callback {
        @Override
        public void onMessageOpen(long messageId, long messageMailboxId, long listMailboxId,
                int type) {
            if (type == MessageListFragment.Callback.TYPE_DRAFT) {
                MessageCompose.actionEditDraft(MessageListXL.this, messageId);
            } else {
                // TODO Disable reply/forward for messages in trash.
                // First, need to figure out what to do with these buttons for MessageViewFragment.
                mFragmentManager.selectMessage(messageId);
            }
        }

        @Override
        public void onMailboxNotFound() {
            // TODO: What to do??
        }
    }

    private class MessageViewFragmentCallback implements MessageViewFragment.Callback {
        @Override
        public boolean onUrlInMessageClicked(String url) {
            return ActivityHelper.openUrlInMessage(MessageListXL.this, url,
                    mFragmentManager.getAccountId());
        }

        @Override
        public void onMessageSetUnread() {
            mFragmentManager.goBackToMailbox();
        }

        @Override
        public void onMessageNotExists() {
            mFragmentManager.goBackToMailbox();
        }

        @Override
        public void onLoadMessageStarted() {
            // We show indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageFinished() {
            // We hide indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onLoadMessageError() {
            // We hide indeterminate progress on one-pane.
            // TODO Any nice UI for this?
        }

        @Override
        public void onRespondedToInvite(int response) {
            if (!moveToOlder()) {
                // if this is the last message, move up to message-list.
                mFragmentManager.goBackToMailbox();
            }
        }

        @Override
        public void onCalendarLinkClicked(long epochEventStartTime) {
            ActivityHelper.openCalendar(MessageListXL.this, epochEventStartTime);
        }
    }

    @Override
    public void onMessageViewFragmentShown(long accountId, long mailboxId, long messageId) {
        mMessageViewButtonPanel.setVisibility(View.VISIBLE);

        updateMessageOrderManager();
        updateNavigationArrows();
    }

    @Override
    public void onMessageViewFragmentHidden() {
        mMessageViewButtonPanel.setVisibility(View.GONE);

        stopMessageOrderManager();
    }

    @Override
    public void onAccountSecurityHold() {
        // TODO: implement this
    }

    private void loadAccounts() {
        getLoaderManager().initLoader(LOADER_ID_ACCOUNT_LIST, null, new LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return AccountSelectorAdapter.createLoader(mContext);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                updateAccountList(data);
            }
        });
    }

    private void updateAccountList(Cursor accountsCursor) {
        final int count = accountsCursor.getCount();
        if (count == 0) {
            onNoAccountFound();
            return;
        }

        // If ony one acount, don't show dropdown.
        final ActionBar ab = getActionBar();
        if (count == 1) {
            accountsCursor.moveToFirst();
            ab.setStandardNavigationMode();
            ab.setTitle(AccountSelectorAdapter.getAccountName(accountsCursor));
            return;
        }

        // Find the currently selected account, and select it.
        int defaultSelection = 0;
        if (mFragmentManager.isAccountSelected()) {
            accountsCursor.moveToPosition(-1);
            int i = 0;
            while (accountsCursor.moveToNext()) {
                final long accountId = AccountSelectorAdapter.getAccountId(accountsCursor);
                if (accountId == mFragmentManager.getAccountId()) {
                    defaultSelection = i;
                    break;
                }
                i++;
            }
        }

        // Update the dropdown list.
        mAccountsSelectorAdapter.changeCursor(accountsCursor);
        if (ab.getNavigationMode() != ActionBar.NAVIGATION_MODE_DROPDOWN_LIST) {
            ab.setDropdownNavigationMode(mAccountsSelectorAdapter,
                    mActionBarNavigationCallback, defaultSelection);
        }
    }

    private class ActionBarNavigationCallback implements ActionBar.NavigationCallback {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long accountId) {
            if (Email.DEBUG) Log.d(Email.LOG_TAG, "Account selected: accountId=" + accountId);
            mFragmentManager.selectAccount(accountId, -1, true);
            return true;
        }
    }

    private class RefreshListener
            implements RefreshManager.Listener {
        @Override
        public void onMessagingError(long accountId, long mailboxId, String message) {
            Utility.showToast(MessageListXL.this, message); // STOPSHIP temporary UI
            invalidateOptionsMenu();
        }

        @Override
        public void onRefreshStatusChanged(long accountId, long mailboxId) {
            invalidateOptionsMenu();
        }
    }

    private boolean isProgressActive() {
        final long mailboxId = mFragmentManager.getMailboxId();
        return (mailboxId >= 0) && mRefreshManager.isMessageListRefreshing(mailboxId);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_list_xl_option, menu);
        return true;
    }

    // STOPSHIP - this is a placeholder if/until there's support for progress in actionbar
    // Remove it, or replace with a better icon
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.refresh);
        if (isProgressActive()) {
            item.setIcon(android.R.drawable.progress_indeterminate_horizontal);
        } else {
            item.setIcon(R.drawable.ic_menu_refresh);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.compose:
                return onCompose();
            case R.id.refresh:
                onRefresh();
                return true;
            case R.id.account_settings:
                return onAccountSettings();
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean onCompose() {
        if (!mFragmentManager.isAccountSelected()) {
            return false; // this shouldn't really happen
        }
        MessageCompose.actionCompose(this, mFragmentManager.getAccountId());
        return true;
    }

    private boolean onAccountSettings() {
        AccountSettingsXL.actionSettings(this, mFragmentManager.getAccountId());
        return true;
    }

    private boolean onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
        return true;
    }

    private void onRefresh() {
        // Cancel previously running instance if any.
        Utility.cancelTaskInterrupt(mRefreshTask);
        mRefreshTask = new RefreshTask(this, mFragmentManager.getAccountId(),
                mFragmentManager.getMailboxId());
        mRefreshTask.execute();
    }

    /**
     * Class to handle refresh.
     *
     * When the user press "refresh",
     * <ul>
     *   <li>Refresh the current mailbox, if it's refreshable.  (e.g. don't refresh combined inbox,
     *       drafts, etc.
     *   <li>Refresh the mailbox list, if it hasn't been refreshed in the last
     *       {@link #MAILBOX_REFRESH_MIN_INTERVAL}.
     *   <li>Refresh inbox, if it's not the current mailbox and it hasn't been refreshed in the last
     *       {@link #INBOX_AUTO_REFRESH_MIN_INTERVAL}.
     * </ul>
     */
    /* package */ static class RefreshTask extends AsyncTask<Void, Void, Boolean> {
        private final Clock mClock;
        private final Context mContext;
        private final long mAccountId;
        private final long mMailboxId;
        private final RefreshManager mRefreshManager;
        /* package */ long mInboxId;

        public RefreshTask(Context context, long accountId, long mailboxId) {
            this(context, accountId, mailboxId, Clock.INSTANCE,
                    RefreshManager.getInstance(context));
        }

        /* package */ RefreshTask(Context context, long accountId, long mailboxId, Clock clock,
                RefreshManager refreshManager) {
            mClock = clock;
            mContext = context;
            mRefreshManager = refreshManager;
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }

        /**
         * Do DB access on a worker thread.
         */
        @Override
        protected Boolean doInBackground(Void... params) {
            mInboxId = Account.getInboxId(mContext, mAccountId);
            return Mailbox.isRefreshable(mContext, mMailboxId);
        }

        /**
         * Do the actual refresh.
         */
        @Override
        protected void onPostExecute(Boolean isCurrentMailboxRefreshable) {
            if (isCancelled() || isCurrentMailboxRefreshable == null) {
                return;
            }
            if (isCurrentMailboxRefreshable) {
                mRefreshManager.refreshMessageList(mAccountId, mMailboxId);
            }
            // Refresh mailbox list
            if (mAccountId != -1) {
                if (shouldRefreshMailboxList()) {
                    mRefreshManager.refreshMailboxList(mAccountId);
                }
            }
            // Refresh inbox
            if (shouldAutoRefreshInbox()) {
                mRefreshManager.refreshMessageList(mAccountId, mInboxId);
            }
        }

        /**
         * @return true if the mailbox list of the current account hasn't been refreshed
         * in the last {@link #MAILBOX_REFRESH_MIN_INTERVAL}.
         */
        /* package */ boolean shouldRefreshMailboxList() {
            if (mRefreshManager.isMailboxListRefreshing(mAccountId)) {
                return false;
            }
            final long nextRefreshTime = mRefreshManager.getLastMailboxListRefreshTime(mAccountId)
                    + MAILBOX_REFRESH_MIN_INTERVAL;
            if (nextRefreshTime > mClock.getTime()) {
                return false;
            }
            return true;
        }

        /**
         * @return true if the inbox of the current account hasn't been refreshed
         * in the last {@link #INBOX_AUTO_REFRESH_MIN_INTERVAL}.
         */
        /* package */ boolean shouldAutoRefreshInbox() {
            if (mInboxId == mMailboxId) {
                return false; // Current ID == inbox.  No need to auto-refresh.
            }
            if (mRefreshManager.isMessageListRefreshing(mInboxId)) {
                return false;
            }
            final long nextRefreshTime = mRefreshManager.getLastMessageListRefreshTime(mInboxId)
                    + INBOX_AUTO_REFRESH_MIN_INTERVAL;
            if (nextRefreshTime > mClock.getTime()) {
                return false;
            }
            return true;
        }
    }

    // TODO It's a temporary implementation.  See {@link MoveMessagetoDialog}
    @Override
    public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds) {
        ActivityHelper.moveMessages(this, newMailboxId, messageIds);
        if (!moveToOlder()) {
            // if this is the last message, move up to message-list.
            mFragmentManager.goBackToMailbox();
        }
    }
}