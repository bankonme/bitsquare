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

package io.bitsquare.payment;

import io.bitsquare.app.Version;
import io.bitsquare.locale.CountryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class SepaAccountContractData extends PaymentAccountContractData implements Serializable {
    // That object is sent over the wire, so we need to take care of version compatibility.
    private static final long serialVersionUID = Version.NETWORK_PROTOCOL_VERSION;

    transient private static final Logger log = LoggerFactory.getLogger(SepaAccountContractData.class);

    private String holderName;
    private String iban;
    private String bic;
    private Set<String> acceptedCountryCodes;

    public SepaAccountContractData(String paymentMethod, String id, int maxTradePeriod) {
        super(paymentMethod, id, maxTradePeriod);
        acceptedCountryCodes = CountryUtil.getAllSepaCountries().stream().map(e -> e.code).collect(Collectors.toSet());
    }

    public void setHolderName(String holderName) {
        this.holderName = holderName;
    }

    public String getHolderName() {
        return holderName;
    }

    public void setIban(String iban) {
        this.iban = iban;
    }

    public String getIban() {
        return iban;
    }

    public void setBic(String bic) {
        this.bic = bic;
    }

    public String getBic() {
        return bic;
    }

    public void addAcceptedCountry(String countryCode) {
        acceptedCountryCodes.add(countryCode);
    }

    public void removeAcceptedCountry(String countryCode) {
        acceptedCountryCodes.remove(countryCode);
    }

    public List<String> getAcceptedCountryCodes() {
        List<String> sortedList = new ArrayList<>(acceptedCountryCodes);
        sortedList.sort((a, b) -> a.compareTo(b));
        return sortedList;
    }

    @Override
    public String getPaymentDetails() {
        return "SEPA - Holder name: " + holderName + ", IBAN: " + iban + ", BIC: " + bic + ", country code: " + getCountryCode();
    }
}