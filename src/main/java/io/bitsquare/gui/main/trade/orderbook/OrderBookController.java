/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.trade.orderbook;

import io.bitsquare.bank.BankAccountType;
import io.bitsquare.btc.WalletFacade;
import io.bitsquare.gui.CachedViewController;
import io.bitsquare.gui.CodeBehind;
import io.bitsquare.gui.NavigationController;
import io.bitsquare.gui.NavigationItem;
import io.bitsquare.gui.OverlayController;
import io.bitsquare.gui.ViewController;
import io.bitsquare.gui.components.Popups;
import io.bitsquare.gui.main.trade.createoffer.CreateOfferViewCB;
import io.bitsquare.gui.main.trade.takeoffer.TakeOfferController;
import io.bitsquare.gui.util.BSFormatter;
import io.bitsquare.gui.util.ImageUtil;
import io.bitsquare.locale.BSResources;
import io.bitsquare.locale.Country;
import io.bitsquare.locale.CurrencyUtil;
import io.bitsquare.msg.MessageFacade;
import io.bitsquare.persistence.Persistence;
import io.bitsquare.settings.Settings;
import io.bitsquare.trade.Direction;
import io.bitsquare.trade.Offer;
import io.bitsquare.user.User;
import io.bitsquare.util.Utilities;

import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.core.Transaction;
import com.google.bitcoin.utils.Fiat;

import com.google.common.util.concurrent.FutureCallback;

import java.net.URL;

import java.text.DecimalFormat;
import java.text.ParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.inject.Inject;

import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.util.Callback;

import org.controlsfx.control.action.AbstractAction;
import org.controlsfx.control.action.Action;
import org.controlsfx.dialog.Dialog;

import org.jetbrains.annotations.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrderBookController extends CachedViewController {
    private static final Logger log = LoggerFactory.getLogger(OrderBookController.class);

    private NavigationController navigationController;
    private OverlayController overlayController;
    private final OrderBook orderBook;
    private final OrderBookFilter orderBookFilter;
    private final User user;
    private final MessageFacade messageFacade;
    private final WalletFacade walletFacade;
    private final Settings settings;
    private final Persistence persistence;

    private SortedList<OrderBookListItem> offerList;
    private AnimationTimer pollingTimer;

    private final Image buyIcon = ImageUtil.getIconImage(ImageUtil.BUY);
    private final Image sellIcon = ImageUtil.getIconImage(ImageUtil.SELL);

    @FXML public HBox topHBox;
    @FXML public TextField volume, amount, price;
    @FXML public TableView<OrderBookListItem> orderBookTable;
    @FXML public TableColumn<OrderBookListItem, String> priceColumn, amountColumn, volumeColumn;
    @FXML public Button createOfferButton;
    @FXML private TableColumn<String, OrderBookListItem> directionColumn, countryColumn, bankAccountTypeColumn;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    private OrderBookController(NavigationController navigationController,
                                OverlayController overlayController,
                                OrderBook orderBook, User user,
                                MessageFacade messageFacade,
                                WalletFacade walletFacade, Settings settings, Persistence persistence) {
        this.navigationController = navigationController;
        this.overlayController = overlayController;
        this.orderBook = orderBook;
        this.user = user;
        this.messageFacade = messageFacade;
        this.walletFacade = walletFacade;
        this.settings = settings;
        this.persistence = persistence;

        this.orderBookFilter = new OrderBookFilter();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Lifecycle
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        super.initialize(url, rb);

        // init table
        setCountryColumnCellFactory();
        setBankAccountTypeColumnCellFactory();
        setDirectionColumnCellFactory();
    }

    @Override
    public void deactivate() {
        super.deactivate();

        orderBook.cleanup();

        orderBookTable.setItems(null);
        orderBookTable.getSortOrder().clear();
        offerList.comparatorProperty().unbind();

        if (pollingTimer != null) {
            pollingTimer.stop();
            pollingTimer = null;
        }
    }

    @Override
    public void activate() {
        super.activate();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Navigation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void setParentController(ViewController parentController) {
        super.setParentController(parentController);
    }

    @Override
    public Initializable loadViewAndGetChildController(NavigationItem navigationItem) {
        return null;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void init() {
        orderBook.init();
        offerList = orderBook.getOfferList();
        offerList.comparatorProperty().bind(orderBookTable.comparatorProperty());
        orderBookTable.setItems(offerList);
        orderBookTable.getSortOrder().add(priceColumn);
        orderBookTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        orderBook.loadOffers();

        // handlers
        amount.textProperty().addListener((observable, oldValue, newValue) -> {
            orderBookFilter.setAmount(BSFormatter.parseToCoin(newValue));
            updateVolume();
        });

        price.textProperty().addListener((observable, oldValue, newValue) -> {
            orderBookFilter.setPrice(BSFormatter.parseToFiat(newValue));
            updateVolume();
        });

        orderBookFilter.getDirectionChangedProperty().addListener((observable) -> applyOffers());

        user.currentBankAccountProperty().addListener((ov) -> orderBook.loadOffers());

        createOfferButton.setOnAction(e -> createOffer());

        //TODO do polling until broadcast works
        setupPolling();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void applyDirection(Direction direction) {
        init();
        orderBookTable.getSelectionModel().clearSelection();
        price.setText("");
        orderBookFilter.setDirection(direction);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // UI handlers
    ///////////////////////////////////////////////////////////////////////////////////////////

    @FXML
    public void createOffer() {
        if (isRegistered()) {
            createOfferButton.setDisable(true);

            //TODO Remove that when all UIs are converted to CodeBehind
            Initializable nextController = null;
            if (parentController != null) {
                if (parentController instanceof ViewController)
                    nextController = ((ViewController) parentController).loadViewAndGetChildController(NavigationItem
                            .CREATE_OFFER);
                else if (parentController instanceof CodeBehind)
                    nextController = ((CodeBehind) parentController).loadView(NavigationItem
                            .CREATE_OFFER);
            }

            if (nextController != null)
                ((CreateOfferViewCB) nextController).setOrderBookFilter(orderBookFilter);
        }
        else {
            openSetupScreen();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean isRegistered() {
        return user.getAccountId() != null;
    }

    private boolean areSettingsValid() {
        return !settings.getAcceptedLanguageLocales().isEmpty() &&
                !settings.getAcceptedCountries().isEmpty() &&
                !settings.getAcceptedArbitrators().isEmpty() &&
                user.getCurrentBankAccount() != null;
    }


    private void openSetupScreen() {
        overlayController.blurContent();
        List<Action> actions = new ArrayList<>();
        actions.add(new AbstractAction(BSResources.get("shared.ok")) {
            @Override
            public void handle(ActionEvent actionEvent) {
                Dialog.Actions.OK.handle(actionEvent);
                overlayController.removeBlurContent();
                navigationController.navigationTo(NavigationItem.ACCOUNT, NavigationItem.ACCOUNT_SETUP);
            }
        });
        Popups.openInfo("You need to setup your trading account before you can trade.",
                "You don't have a trading account.", actions);
    }

    private void payRegistrationFee() {
        FutureCallback<Transaction> callback = new FutureCallback<Transaction>() {
            @Override
            public void onSuccess(@javax.annotation.Nullable Transaction transaction) {
                log.debug("payRegistrationFee onSuccess");
                if (transaction != null) {
                    log.info("payRegistrationFee onSuccess tx id:" + transaction.getHashAsString());
                }
            }

            @Override
            public void onFailure(@NotNull Throwable t) {
                log.debug("payRegistrationFee onFailure");
            }
        };
        try {
            walletFacade.payRegistrationFee(user.getStringifiedBankAccounts(), callback);
            if (walletFacade.getRegistrationAddressEntry() != null) {
                user.setAccountID(walletFacade.getRegistrationAddressEntry().toString());
            }

            persistence.write(user.getClass().getName(), user);
        } catch (InsufficientMoneyException e1) {
            Popups.openInsufficientMoneyPopup();
        }
    }

    private void takeOffer(Offer offer) {
        if (isRegistered()) {

            //TODO Remove that when all UIs are converted to CodeBehind
            TakeOfferController takeOfferController = null;
            if (parentController != null) {
                if (parentController instanceof ViewController)
                    takeOfferController = (TakeOfferController) ((ViewController) parentController)
                            .loadViewAndGetChildController(NavigationItem
                                    .TAKE_OFFER);
                else if (parentController instanceof CodeBehind)
                    takeOfferController = (TakeOfferController) ((CodeBehind) parentController)
                            .loadView(NavigationItem
                                    .TAKE_OFFER);
            }

            Coin requestedAmount;
            if (!"".equals(amount.getText())) {
                requestedAmount = BSFormatter.parseToCoin(amount.getText());
            }
            else {
                requestedAmount = offer.getAmount();
            }

            if (takeOfferController != null) {
                takeOfferController.initWithData(offer, requestedAmount);
            }
        }
        else {
            openSetupScreen();
        }
    }

    private void removeOffer(Offer offer) {
        orderBook.removeOffer(offer);
    }

    private void applyOffers() {
        orderBook.applyFilter(orderBookFilter);

        priceColumn.setSortType((orderBookFilter.getDirection() == Direction.BUY) ?
                TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
        orderBookTable.sort();

        if (orderBookTable.getItems() != null) {
            createOfferButton.setDefaultButton(orderBookTable.getItems().isEmpty());
        }
    }

    private void setupPolling() {
        pollingTimer = Utilities.setInterval(1000, (animationTimer) -> {
            if (user.getCurrentBankAccount() != null) {
                messageFacade.getDirtyFlag(user.getCurrentBankAccount().getCurrency());
            }
            else {
                messageFacade.getDirtyFlag(CurrencyUtil.getDefaultCurrency());
            }
            return null;
        });

        messageFacade.getIsDirtyProperty().addListener((observableValue, oldValue, newValue) -> orderBook.loadOffers());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Table columns
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setDirectionColumnCellFactory() {
        directionColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper(offer.getValue()));
        directionColumn.setCellFactory(
                new Callback<TableColumn<String, OrderBookListItem>, TableCell<String, OrderBookListItem>>() {

                    @Override
                    public TableCell<String, OrderBookListItem> call(
                            TableColumn<String, OrderBookListItem> directionColumn) {
                        return new TableCell<String, OrderBookListItem>() {
                            final ImageView iconView = new ImageView();
                            final Button button = new Button();

                            {
                                button.setGraphic(iconView);
                                button.setMinWidth(70);
                            }

                            @Override
                            public void updateItem(final OrderBookListItem orderBookListItem, boolean empty) {
                                super.updateItem(orderBookListItem, empty);

                                if (orderBookListItem != null) {
                                    String title;
                                    Image icon;
                                    Offer offer = orderBookListItem.getOffer();

                                    if (offer.getMessagePublicKey().equals(user.getMessagePublicKey())) {
                                        icon = ImageUtil.getIconImage(ImageUtil.REMOVE);
                                        title = "Remove";
                                        button.setOnAction(event -> removeOffer(orderBookListItem.getOffer()));
                                    }
                                    else {
                                        if (offer.getDirection() == Direction.SELL) {
                                            icon = buyIcon;
                                            title = BSFormatter.formatDirection(Direction.BUY, true);
                                        }
                                        else {
                                            icon = sellIcon;
                                            title = BSFormatter.formatDirection(Direction.SELL, true);
                                        }

                                        button.setDefaultButton(getIndex() == 0);
                                        button.setOnAction(event -> takeOffer(orderBookListItem.getOffer()));
                                    }


                                    iconView.setImage(icon);
                                    button.setText(title);
                                    setGraphic(button);
                                }
                                else {
                                    setGraphic(null);
                                }
                            }
                        };
                    }
                });
    }

    private void setCountryColumnCellFactory() {
        countryColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper(offer.getValue()));
        countryColumn.setCellFactory(
                new Callback<TableColumn<String, OrderBookListItem>, TableCell<String, OrderBookListItem>>() {

                    @Override
                    public TableCell<String, OrderBookListItem> call(
                            TableColumn<String, OrderBookListItem> directionColumn) {
                        return new TableCell<String, OrderBookListItem>() {
                            final HBox hBox = new HBox();

                            {
                                hBox.setSpacing(3);
                                hBox.setAlignment(Pos.CENTER);
                                setGraphic(hBox);
                            }

                            @Override
                            public void updateItem(final OrderBookListItem orderBookListItem, boolean empty) {
                                super.updateItem(orderBookListItem, empty);

                                hBox.getChildren().clear();
                                if (orderBookListItem != null) {
                                    Country country = orderBookListItem.getOffer().getBankAccountCountry();
                                    try {
                                        hBox.getChildren().add(ImageUtil.getIconImageView(
                                                "/images/countries/" + country.getCode().toLowerCase() + ".png"));

                                    } catch (Exception e) {
                                        log.warn("Country icon not found: /images/countries/" +
                                                country.getCode().toLowerCase() + ".png country name: " +
                                                country.getName());
                                    }
                                    Tooltip.install(this, new Tooltip(country.getName()));
                                }
                            }
                        };
                    }
                });
    }

    private void setBankAccountTypeColumnCellFactory() {
        bankAccountTypeColumn.setCellValueFactory((offer) -> new ReadOnlyObjectWrapper(offer.getValue()));
        bankAccountTypeColumn.setCellFactory(
                new Callback<TableColumn<String, OrderBookListItem>, TableCell<String, OrderBookListItem>>() {

                    @Override
                    public TableCell<String, OrderBookListItem> call(
                            TableColumn<String, OrderBookListItem> directionColumn) {
                        return new TableCell<String, OrderBookListItem>() {
                            @Override
                            public void updateItem(final OrderBookListItem orderBookListItem, boolean empty) {
                                super.updateItem(orderBookListItem, empty);

                                if (orderBookListItem != null) {
                                    BankAccountType bankAccountType = orderBookListItem.getOffer().getBankAccountType();
                                    setText(BSResources.get(bankAccountType.toString()));
                                }
                                else {
                                    setText("");
                                }
                            }
                        };
                    }
                });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////////////////////


    private double textInputToNumber(String oldValue, String newValue) {
        //TODO use regex.... or custom textfield component
        double d = 0.0;
        if (!"".equals(newValue)) {
            try {
                DecimalFormat decimalFormat = (DecimalFormat) DecimalFormat.getInstance(Locale.getDefault());
                d = decimalFormat.parse(newValue).doubleValue();
            } catch (ParseException e) {
                amount.setText(oldValue);
                d = BSFormatter.parseToDouble(oldValue);
            }
        }
        return d;
    }

    private void updateVolume() {
        double a = textInputToNumber(amount.getText(), amount.getText());
        double p = textInputToNumber(price.getText(), price.getText());
        //TODO
        volume.setText(BSFormatter.formatFiat(Fiat.valueOf("EUR", (long) (a * p))));
    }

    public void onCreateOfferViewRemoved() {
        createOfferButton.setDisable(false);
    }

}
