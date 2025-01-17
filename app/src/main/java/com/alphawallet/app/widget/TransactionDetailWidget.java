package com.alphawallet.app.widget;

/**
 * Created by JB on 15/01/2021.
 */

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.alphawallet.app.R;
import com.alphawallet.app.entity.ActionSheetInterface;
import com.alphawallet.app.web3.entity.Web3Transaction;
import com.alphawallet.token.tools.Numeric;

import org.jetbrains.annotations.NotNull;

public class TransactionDetailWidget extends LinearLayout
{
    private final TextView textTransactionSummary;
    private final TextView textFullDetails;
    private final TextView textFunctionName;
    private final LinearLayout layoutDetails;
    private final LinearLayout layoutHolder;
    private final LinearLayout layoutHeader;
    private final ProgressBar progressBar;
    private ActionSheetInterface sheetInterface;

    public TransactionDetailWidget(Context context, @Nullable AttributeSet attrs)
    {
        super(context, attrs);
        inflate(context, R.layout.transaction_detail_widget, this);
        textTransactionSummary = findViewById(R.id.text_transaction_summary);
        textFullDetails = findViewById(R.id.text_full_details);
        textFunctionName = findViewById(R.id.text_function_name);
        layoutDetails = findViewById(R.id.layout_detail);
        layoutHolder = findViewById(R.id.layout_holder);
        layoutHeader = findViewById(R.id.layout_header);
        progressBar = findViewById(R.id.progress);
    }

    public void setupTransaction(Web3Transaction w3tx, long chainId, String symbol,
                                 @NotNull ActionSheetInterface asIf, String functionName)
    {
        progressBar.setVisibility(View.GONE);
        textTransactionSummary.setVisibility(View.VISIBLE);
        textFullDetails.setText(w3tx.getFormattedTransaction(getContext(), chainId, symbol));
        sheetInterface = asIf;

        if (!TextUtils.isEmpty(functionName))
        {
            textTransactionSummary.setText(functionName);
            textFunctionName.setText(functionName);
        }
        else if (!TextUtils.isEmpty(w3tx.description))
        {
            textTransactionSummary.setText(w3tx.description);
        }
        else
        {
            String displayText = ("0x" + Numeric.cleanHexPrefix(w3tx.payload)).substring(0, 10);
            textTransactionSummary.setText(displayText);
            textFunctionName.setText(displayText);
        }

        layoutHolder.setOnClickListener(v -> {
            if (layoutDetails.getVisibility() == View.GONE)
            {
                layoutDetails.setVisibility(View.VISIBLE);
                layoutHeader.setVisibility(View.GONE);
                sheetInterface.lockDragging(true);
            }
            else
            {
                layoutDetails.setVisibility(View.GONE);
                layoutHeader.setVisibility(View.VISIBLE);
                sheetInterface.lockDragging(false);
            }
        });
    }
}
