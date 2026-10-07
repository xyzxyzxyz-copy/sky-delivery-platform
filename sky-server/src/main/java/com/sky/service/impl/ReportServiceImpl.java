package com.sky.service.impl;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.*;
import lombok.val;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportServiceImpl implements ReportService {
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private WorkspaceService workspaceService;
    @Override
    public TurnoverReportVO turnoverStatistics(LocalDate start, LocalDate end) {
        List<LocalDate> dateList = new ArrayList<>();
        LocalDate current = start;
        dateList.add(current);
        while (current.isBefore(end)) {
            current = current.plusDays(1);
            dateList.add(current);
        }
        String date = StringUtils.join(dateList, ",");

        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate localDate : dateList) {
            LocalDateTime beginTime = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

            Double sum = orderMapper.sumTurn(beginTime, endTime, Orders.COMPLETED);
            sum = sum == null ? 0 : sum;
            turnoverList.add(sum);
        }
        String turn = StringUtils.join(turnoverList, ",");
        return new TurnoverReportVO(date, turn);
    }
    @Override
    public UserReportVO userStatistics(LocalDate begin, LocalDate end){
        List<LocalDate> dateList=new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)){
            begin=begin.plusDays(1);
            dateList.add(begin);
        }
        String date=StringUtils.join(dateList,",");
        List<Integer> newUserList=new ArrayList<>();
        List<Integer> totalUserList=new ArrayList<>();
        for (LocalDate localDate:dateList){
            LocalDateTime beginTime=LocalDateTime.of(localDate,LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);
            Map<String,LocalDateTime> map=new HashMap<>();
            map.put("end",endTime);
            Integer total=orderMapper.getUserStatistics(map);
            map.put("begin",beginTime);
            Integer newUser=orderMapper.getUserStatistics(map);
            newUserList.add(newUser);
            totalUserList.add(total);
        }
        String str_newUser=StringUtils.join(newUserList,",");
        String str_totalUser=StringUtils.join(totalUserList,",");
        return new UserReportVO(date,str_totalUser,str_newUser);
    }
    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end){
        List<LocalDate> dateList=new ArrayList<>();
        dateList.add(begin);
        while (!begin.equals(end)){
            begin=begin.plusDays(1);
            dateList.add(begin);
        }
        String date=StringUtils.join(dateList,",");
        List<Integer> orderCountList=new ArrayList<>();
        List<Integer> validOrderCountList=new ArrayList<>();
        Integer orderCountTotal=0;
        Integer validOrderCountTotal=0;
        for (LocalDate localDate:dateList){
            LocalDateTime beginTime=LocalDateTime.of(localDate,LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);
            Integer orderTotal=orderCount(beginTime,endTime,null);
            Integer orderValid=orderCount(beginTime,endTime,5);
            orderCountList.add(orderTotal);
            validOrderCountList.add(orderValid);
            orderCountTotal=orderCountTotal+orderTotal;
            validOrderCountTotal=validOrderCountTotal+orderValid;
        }
        String orderCount=StringUtils.join(orderCountList,",");
        String validCount=StringUtils.join(validOrderCountList,",");
        Double orderCompletionRate=0.0;
        if(orderCountTotal!=0){
            orderCompletionRate=validOrderCountTotal.doubleValue()/orderCountTotal;
        }
        return new OrderReportVO(date,orderCount,validCount,orderCountTotal,validOrderCountTotal,orderCompletionRate);
    }
    private Integer orderCount(LocalDateTime begin,LocalDateTime end,Integer status){
        Map map=new HashMap();
        map.put("begin",begin);
        map.put("end",end);
        map.put("status",status);

        return orderMapper.getOrderStatistics(map);
    }
    @Override
    public SalesTop10ReportVO getTop10Statistics(LocalDate begin, LocalDate end){
        LocalDateTime beginTime=LocalDateTime.of(begin,LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        List<GoodsSalesDTO> goodsSalesDTOS=orderMapper.getSalesTop10(beginTime,endTime);
        List<String> names=new ArrayList<>();
        List<Integer> numbers=new ArrayList<>();
        goodsSalesDTOS.forEach(item->{
            names.add(item.getName());
            numbers.add(item.getNumber());
        });
        String nameList=StringUtils.join(names,",");
        String numberList=StringUtils.join(numbers,",");
        return  new SalesTop10ReportVO(nameList,numberList);
    }
    @Override
    public void export(HttpServletResponse response){
        LocalDate nowTime=LocalDate.now();
        LocalDate startTime=nowTime.plusDays(-30);
        LocalDate endTime=nowTime.plusDays(-1);
        BusinessDataVO businessData = workspaceService.getBusinessData(LocalDateTime.of(startTime, LocalTime.MIN), LocalDateTime.of(endTime, LocalTime.MAX));

        InputStream inputStream=this.getClass().getClassLoader().getResourceAsStream("template/运营数据报表模板.xlsx");
        if (inputStream == null) {
            throw new RuntimeException("报表模板文件不存在：template/运营数据报表模板.xlsx");
        }
        try {
            XSSFWorkbook excel=new XSSFWorkbook(inputStream);
            XSSFSheet sheet=excel.getSheet("Sheet1");
            sheet.getRow(1).getCell(1).setCellValue("时间"+startTime+"到"+endTime);
            XSSFRow row=sheet.getRow(3);
            row.getCell(2).setCellValue(businessData.getTurnover());
            row.getCell(4).setCellValue(businessData.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessData.getNewUsers());
            row=sheet.getRow(4);
            row.getCell(2).setCellValue(businessData.getValidOrderCount());
            row.getCell(4).setCellValue(businessData.getUnitPrice());
            //填充明细数据
            for (int i = 0; i < 30; i++) {
                LocalDate date = startTime.plusDays(i);
                //查询某一天的营业数据
                BusinessDataVO businessData1 = workspaceService.getBusinessData(LocalDateTime.of(date, LocalTime.MIN), LocalDateTime.of(date, LocalTime.MAX));

                //获得某一行
                row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(date.toString());
                row.getCell(2).setCellValue(businessData1.getTurnover());
                row.getCell(3).setCellValue(businessData1.getValidOrderCount());
                row.getCell(4).setCellValue(businessData1.getOrderCompletionRate());
                row.getCell(5).setCellValue(businessData1.getUnitPrice());
                row.getCell(6).setCellValue(businessData1.getNewUsers());
            }
            ServletOutputStream out=response.getOutputStream();
            excel.write(out);
            excel.close();
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
