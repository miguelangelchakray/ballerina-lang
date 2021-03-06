/*
*  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing,
*  software distributed under the License is distributed on an
*  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
*  KIND, either express or implied.  See the License for the
*  specific language governing permissions and limitations
*  under the License.
*/
package org.ballerinalang.langserver.completions.util.filters;

import org.antlr.v4.runtime.Token;
import org.ballerinalang.langserver.common.UtilSymbolKeys;
import org.ballerinalang.langserver.common.utils.CommonUtil;
import org.ballerinalang.langserver.compiler.LSServiceOperationContext;
import org.ballerinalang.langserver.completions.CompletionKeys;
import org.ballerinalang.langserver.completions.SymbolInfo;
import org.ballerinalang.langserver.index.LSIndexImpl;
import org.ballerinalang.langserver.index.dao.ObjectDAO;
import org.ballerinalang.langserver.index.dao.OtherTypeDAO;
import org.ballerinalang.langserver.index.dao.PackageFunctionDAO;
import org.ballerinalang.langserver.index.dao.RecordDAO;
import org.ballerinalang.model.elements.PackageID;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.ballerinalang.compiler.semantics.model.Scope;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BInvokableSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BPackageSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BTypeSymbol;
import org.wso2.ballerinalang.compiler.semantics.model.symbols.BVarSymbol;
import org.wso2.ballerinalang.compiler.util.Name;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filter the actions and the functions in a package.
 */
public class PackageActionFunctionAndTypesFilter extends AbstractSymbolFilter {

    private static final Logger logger = LoggerFactory.getLogger(PackageActionFunctionAndTypesFilter.class);

    @Override
    public Either<List<CompletionItem>, List<SymbolInfo>> filterItems(LSServiceOperationContext completionContext) {

        List<String> poppedTokens = CommonUtil
                .popNFromStack(completionContext.get(CompletionKeys.FORCE_CONSUMED_TOKENS_KEY), 3).stream()
                .map(Token::getText)
                .collect(Collectors.toList());

        String delimiter = "";
        for (String poppedToken : poppedTokens) {
            if (poppedTokens.contains(UtilSymbolKeys.DOT_SYMBOL_KEY)
                    || poppedTokens.contains(UtilSymbolKeys.PKG_DELIMITER_KEYWORD)
                    || poppedTokens.contains(UtilSymbolKeys.ACTION_INVOCATION_SYMBOL_KEY)) {
                delimiter = poppedToken;
            }
        }
        
        ArrayList<SymbolInfo> returnSymbolsInfoList = new ArrayList<>();
        String tokenBeforeDelimiter = poppedTokens.get(poppedTokens.indexOf(delimiter) - 1);

        if (UtilSymbolKeys.DOT_SYMBOL_KEY.equals(delimiter)
                || UtilSymbolKeys.ACTION_INVOCATION_SYMBOL_KEY.equals(delimiter)) {
            returnSymbolsInfoList.addAll(CommonUtil.invocationsAndFieldsOnIdentifier(completionContext,
                            tokenBeforeDelimiter, delimiter));
        } else if (UtilSymbolKeys.PKG_DELIMITER_KEYWORD.equals(delimiter)) {
            // We are filtering the package functions, actions and the types
            Either<List<CompletionItem>, List<SymbolInfo>> filteredList = 
                    this.getActionsFunctionsAndTypes(completionContext, tokenBeforeDelimiter);
            if (filteredList.isLeft()) {
                return Either.forLeft(filteredList.getLeft());
            }
            returnSymbolsInfoList.addAll(filteredList.getRight());
        }

        return Either.forRight(returnSymbolsInfoList);
    }

    /**
     * Get the actions, functions and types.
     * @param completionContext     Text Document Service context (Completion Context)
     * @param packageName           Package name to evaluate against
     * @return {@link ArrayList}    List of filtered symbol info
     */
    private Either<List<CompletionItem>, List<SymbolInfo>> getActionsFunctionsAndTypes(
            LSServiceOperationContext completionContext, String packageName) {

        List<SymbolInfo> symbols = completionContext.get(CompletionKeys.VISIBLE_SYMBOLS_KEY);

        // Extract the package symbol
        SymbolInfo packageSymbolInfo = symbols.stream().filter(item -> {
            Scope.ScopeEntry scopeEntry = item.getScopeEntry();
            return item.getSymbolName().equals(packageName) && scopeEntry.symbol instanceof BPackageSymbol;
        }).findFirst().orElse(null);

        if (packageSymbolInfo != null) {
            Scope.ScopeEntry packageEntry = packageSymbolInfo.getScopeEntry();
            PackageID packageID = packageEntry.symbol.pkgID;
            SymbolInfo symbolInfo = new SymbolInfo(packageSymbolInfo.getSymbolName(), packageEntry);
            Map<Name, Scope.ScopeEntry> scopeEntryMap = symbolInfo.getScopeEntry().symbol.scope.entries;

            try {
                List<PackageFunctionDAO> packageFunctionDAOs = LSIndexImpl.getInstance().getQueryProcessor()
                        .getFunctionsFromPackage(packageID.getName().getValue(), packageID.getOrgName().getValue());
                List<RecordDAO> recordDAOs = LSIndexImpl.getInstance().getQueryProcessor()
                        .getRecordsFromPackage(packageID.getName().getValue(), packageID.getOrgName().getValue());
                List<OtherTypeDAO> otherTypeDAOs = LSIndexImpl.getInstance().getQueryProcessor()
                        .getOtherTypesFromPackage(packageID.getName().getValue(), packageID.getOrgName().getValue());
                List<ObjectDAO> objectDAOs = LSIndexImpl.getInstance().getQueryProcessor()
                        .getObjectsFromPackage(packageID.getName().getValue(), packageID.getOrgName().getValue());
                if (packageFunctionDAOs.isEmpty() && recordDAOs.isEmpty() && objectDAOs.isEmpty()) {
                    return Either.forRight(this.loadActionsFunctionsAndTypesFromScope(scopeEntryMap));
                }
                List<CompletionItem> completionItems = packageFunctionDAOs.stream()
                        .map(PackageFunctionDAO::getCompletionItem)
                        .collect(Collectors.toList());
                completionItems.addAll(
                        recordDAOs.stream()
                                .map(RecordDAO::getCompletionItem)
                                .collect(Collectors.toList())
                );
                completionItems.addAll(
                        otherTypeDAOs.stream()
                                .map(OtherTypeDAO::getCompletionItem)
                                .collect(Collectors.toList())
                );
                completionItems.addAll(
                        objectDAOs.stream()
                                .map(ObjectDAO::getCompletionItem)
                                .collect(Collectors.toList())
                );
                return Either.forLeft(completionItems);
            } catch (SQLException e) {
                logger.warn("Error retrieving Completion Items from Index DB.");
                return Either.forRight(this.loadActionsFunctionsAndTypesFromScope(scopeEntryMap));
            }
        }

        return Either.forRight(new ArrayList<>());
    }
    
    private List<SymbolInfo> loadActionsFunctionsAndTypesFromScope(Map<Name, Scope.ScopeEntry> entryMap) {
        List<SymbolInfo> actionFunctionList = new ArrayList<>();
        entryMap.forEach((name, value) -> {
            BSymbol symbol = value.symbol;
            if ((symbol instanceof BInvokableSymbol && ((BInvokableSymbol) symbol).receiverSymbol == null)
                    || (symbol instanceof BTypeSymbol && !(symbol instanceof BPackageSymbol))
                    || symbol instanceof BVarSymbol) {
                SymbolInfo entry = new SymbolInfo(name.toString(), value);
                actionFunctionList.add(entry);
            }
        });
        
        return actionFunctionList;
    }
}
