/*
 * Copyright 2013 Megion Research and Development GmbH
 *
 *  Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 *  This license governs use of the accompanying software. If you use the software, you accept this license.
 *  If you do not accept the license, do not use the software.
 *
 *  1. Definitions
 *  The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 *  "You" means the licensee of the software.
 *  "Your company" means the company you worked for when you downloaded the software.
 *  "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 *  of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 *  software, and specifically excludes the right to distribute the software outside of your company.
 *  "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 *  under this license.
 *
 *  2. Grant of Rights
 *  (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 *  worldwide, royalty-free copyright license to reproduce the software for reference use.
 *  (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 *  worldwide, royalty-free patent license under licensed patents for reference use.
 *
 *  3. Limitations
 *  (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 *  (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 *  (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 *  (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 *  guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 *  change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 *  fitness for a particular purpose and non-infringement.
 *
 */

package com.mycelium.wallet.activity.send;

import java.util.LinkedList;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

import com.mrd.bitlib.StandardTransactionBuilder;
import com.mrd.bitlib.StandardTransactionBuilder.InsufficientFundsException;
import com.mrd.bitlib.StandardTransactionBuilder.OutputTooSmallException;
import com.mrd.bitlib.StandardTransactionBuilder.UnsignedTransaction;
import com.mrd.bitlib.crypto.PrivateKeyRing;
import com.mrd.bitlib.model.Transaction;
import com.mrd.bitlib.model.UnspentTransactionOutput;
import com.mrd.mbwapi.api.ApiError;
import com.mrd.mbwapi.api.BroadcastTransactionResponse;
import com.mrd.mbwapi.api.ExchangeSummary;
import com.mycelium.wallet.Constants;
import com.mycelium.wallet.MbwManager;
import com.mycelium.wallet.R;
import com.mycelium.wallet.Record;
import com.mycelium.wallet.Utils;
import com.mycelium.wallet.activity.send.SendActivityHelper.SendContext;
import com.mycelium.wallet.api.AbstractCallbackHandler;
import com.mycelium.wallet.api.AndroidAsyncApi;
import com.mycelium.wallet.api.AsyncTask;

public class SendSummaryActivity extends Activity {

   public static final int SCANNER_RESULT_CODE = 0;

   private AsyncTask _task;
   private PrivateKeyRing _privateKeyRing;
   private UnsignedTransaction _unsigned;
   private Double _oneBtcInFiat;
   private MbwManager _mbwManager;
   private SendContext _context;

   /** Called when the activity is first created. */
   @SuppressLint("ShowToast")
   @Override
   public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.send_summary_activity);
      _mbwManager = MbwManager.getInstance(getApplication());

      // Get intent parameters
      _context = SendActivityHelper.getSendContext(this);

      findViewById(R.id.btSend).setOnClickListener(new OnClickListener() {

         @Override
         public void onClick(View arg0) {
            _mbwManager.runPinProtectedFunction(SendSummaryActivity.this, pinProtectedSignAndSend);
         }
      });

      createUnsignedTransaction();
      findViewById(R.id.llSynchronizing).setVisibility(View.INVISIBLE);
      // Start task to get exchange rate
      AndroidAsyncApi api = _mbwManager.getAsyncApi();
      _task = api.getExchangeSummary(_mbwManager.getFiatCurrency(), new QueryExchangeSummaryHandler());
      updateUi();
   }

   private void createUnsignedTransaction() {
      // Construct list of outputs
      List<UnspentTransactionOutput> outputs = new LinkedList<UnspentTransactionOutput>();
      outputs.addAll(_context.unspentOutputs.unspent);
      outputs.addAll(_context.unspentOutputs.change);

      // Construct private key ring
      _privateKeyRing = new PrivateKeyRing();
      _privateKeyRing.addPrivateKey(_context.spendingRecord.key, Constants.network);

      // Create unsigned transaction
      StandardTransactionBuilder stb = new StandardTransactionBuilder(Constants.network);

      // Add the output
      try {
         stb.addOutput(_context.receivingAddress, _context.amountToSend);
      } catch (OutputTooSmallException e1) {
         Toast.makeText(this, getResources().getString(R.string.amount_too_small), Toast.LENGTH_LONG).show();
         return;
      }

      // Create the unsigned transaction
      try {
         _unsigned = stb.createUnsignedTransaction(outputs, _context.spendingRecord.address, _privateKeyRing,
               Constants.network);
      } catch (InsufficientFundsException e) {
         Toast.makeText(this, getResources().getString(R.string.insufficient_funds), Toast.LENGTH_LONG).show();
      }

   }

   private void updateUi() {
      // Set Address
      String address = _context.receivingAddress.toString();
      address = address.substring(0, 12) + "\r\n" + address.substring(12, 24) + "\r\n" + address.substring(24);
      ((TextView) findViewById(R.id.tvReceiver)).setText(address);

      // Show / hide warning
      Record record = _mbwManager.getRecordManager().getRecord(_context.receivingAddress);
      if (record != null && !record.hasPrivateKey()) {
         findViewById(R.id.tvWarning).setVisibility(View.VISIBLE);
      } else {
         findViewById(R.id.tvWarning).setVisibility(View.GONE);
      }
      
      // Set Amount
      ((TextView) findViewById(R.id.tvAmount)).setText(_mbwManager.getBtcValueString(_context.amountToSend));
      if (_oneBtcInFiat == null) {
         findViewById(R.id.tvAmountFiat).setVisibility(View.INVISIBLE);
      } else {
         // Set approximate amount in fiat
         TextView tvAmountFiat = ((TextView) findViewById(R.id.tvAmountFiat));
         tvAmountFiat.setText(getFiatValue(_context.amountToSend, _oneBtcInFiat));
         tvAmountFiat.setVisibility(View.VISIBLE);
      }

      if (_unsigned == null) {
         // Hide fee show synchronizing
         findViewById(R.id.llFee).setVisibility(View.INVISIBLE);
      } else {
         // Show and set Fee, and hide synchronizing
         findViewById(R.id.llFee).setVisibility(View.VISIBLE);

         long fee = _unsigned.calculateFee();
         TextView tvFee = (TextView) findViewById(R.id.tvFee);
         tvFee.setText(_mbwManager.getBtcValueString(fee));
         if (_oneBtcInFiat == null) {
            findViewById(R.id.tvFeeFiat).setVisibility(View.INVISIBLE);
         } else {
            // Set approximate fee in fiat
            TextView tvFeeFiat = ((TextView) findViewById(R.id.tvFeeFiat));
            tvFeeFiat.setText(getFiatValue(fee, _oneBtcInFiat));
            tvFeeFiat.setVisibility(View.VISIBLE);
         }
      }

      // Enable/disable send button
      findViewById(R.id.btSend).setEnabled(_unsigned != null);
   }

   private String getFiatValue(long satoshis, Double oneBtcInFiat) {
      String currency = _mbwManager.getFiatCurrency();
      Double converted = Utils.getFiatValue(satoshis, oneBtcInFiat);
      return getResources().getString(R.string.approximate_fiat_value, currency, converted);
   }

   @Override
   protected void onDestroy() {
      cancelEverything();
      super.onDestroy();
   }

   @Override
   protected void onResume() {
      super.onResume();
   }

   private void cancelEverything() {
      if (_task != null) {
         _task.cancel();
         _task = null;
      }
   }

   final Runnable pinProtectedSignAndSend = new Runnable() {

      @Override
      public void run() {
         signAndSendTransaction();
      }
   };

   private void signAndSendTransaction() {
      findViewById(R.id.pbSend).setVisibility(View.VISIBLE);
      findViewById(R.id.btSend).setEnabled(false);

      // Sign transaction in the background
      new android.os.AsyncTask<Handler, Integer, Void>() {

         @Override
         protected Void doInBackground(Handler... handler) {
            _unsigned.getSignatureInfo();
            List<byte[]> signatures = StandardTransactionBuilder.generateSignatures(_unsigned.getSignatureInfo(),
                  _privateKeyRing);
            final Transaction tx = StandardTransactionBuilder.finalizeTransaction(_unsigned, signatures);
            // execute broadcasting task from UI thread
            handler[0].post(new Runnable() {

               @Override
               public void run() {
                  AndroidAsyncApi api = _mbwManager.getAsyncApi();
                  _task = api.broadcastTransaction(tx, new BroadcastTransactionHandler());
               }
            });
            return null;
         }
      }.execute(new Handler[] { new Handler() });
   }

   class BroadcastTransactionHandler implements AbstractCallbackHandler<BroadcastTransactionResponse> {

      @Override
      public void handleCallback(BroadcastTransactionResponse response, ApiError exception) {
         _task = null;
         Activity me = SendSummaryActivity.this;
         if (exception != null) {
            Toast.makeText(me, getResources().getString(R.string.transaction_not_sent), Toast.LENGTH_LONG).show();
            findViewById(R.id.pbSend).setVisibility(View.INVISIBLE);
            findViewById(R.id.btSend).setEnabled(true);
            return;
         } else {
            Toast.makeText(me, getResources().getString(R.string.transaction_sent), Toast.LENGTH_LONG).show();
         }
         // Include the transaction hash in the response
         Intent result = new Intent();
         result.putExtra("transaction_hash", response.hash.toString());
         setResult(RESULT_OK, result);
         SendSummaryActivity.this.finish();
      }
   }

   class QueryExchangeSummaryHandler implements AbstractCallbackHandler<ExchangeSummary[]> {

      @Override
      public void handleCallback(ExchangeSummary[] response, ApiError exception) {
         _task = null;
         if (exception != null) {
            Utils.toastConnectionError(SendSummaryActivity.this);
            _oneBtcInFiat = null;
         } else {
            _oneBtcInFiat = Utils.getLastTrade(response);
            updateUi();
         }
      }

   }

}