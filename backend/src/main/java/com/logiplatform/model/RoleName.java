package com.logiplatform.model;

public enum RoleName {

        ADMIN(
                        "Administrator",
                        "Full platform administration, user management and governance."),

        MANAGER(
                        "Manager",
                        "Operational and commercial management across the workspace."),

        OPERATIONS(
                        "Operations",
                        "Shipment execution, operational control and exception management."),

        SALES(
                        "Sales",
                        "Customers, quotations and commercial pipeline."),

        FINANCE(
                        "Finance",
                        "Billing, receivables, invoices and financial control."),

        DISPATCH(
                        "Dispatch",
                        "Fleet, drivers, trips and dispatch execution."),

        WAREHOUSE(
                        "Warehouse",
                        "Warehouse and inventory execution."),

        AIR_CARGO(
                        "Air Cargo",
                        "Air cargo booking, flights, AWB and air-freight execution."),

        CUSTOMER(
                        "Customer",
                        "Customer portal access for shipments, quotations, documents and invoices.");

        private final String displayName;
        private final String description;

        RoleName(
                        String displayName,
                        String description) {

                this.displayName = displayName;
                this.description = description;
        }

        public String getDisplayName() {
                return displayName;
        }

        public String getDescription() {
                return description;
        }

        public static RoleName from(String value) {

                if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException("Role is required");
                }

                try {
                        return RoleName.valueOf(
                                        value.trim().toUpperCase());
                } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException(
                                        "Unsupported role: " + value);
                }
        }
}