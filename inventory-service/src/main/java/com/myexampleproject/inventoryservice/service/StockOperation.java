package com.myexampleproject.inventoryservice.service;
import com.myexampleproject.common.event.InventoryAdjustmentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
public record StockOperation(String operationId,String skuCode,int adjustmentQuantity,String reason,String status,Integer quantity,String code) {
    private static final ObjectMapper MAPPER=new ObjectMapper();
    public static StockOperation of(InventoryAdjustmentEvent e,String status,Integer quantity,String code) {
        return new StockOperation(e.getOperationId(),e.getSkuCode(),e.getAdjustmentQuantity(),e.getReason(),status,quantity,code);
    }
    public boolean matches(InventoryAdjustmentEvent e) {return skuCode.equals(e.getSkuCode()) && adjustmentQuantity==e.getAdjustmentQuantity() && java.util.Objects.equals(reason,e.getReason());}
    public String json() {try{return MAPPER.writeValueAsString(this);}catch(Exception ex){throw new IllegalStateException("Cannot serialize stock operation");}}
    public static StockOperation parse(String json) {try{return MAPPER.readValue(json,StockOperation.class);}catch(Exception ex){throw new IllegalStateException("Invalid stock operation record");}}
}
