/**
 * 个人档案：一个用户<b>唯一一份</b>的全局信息与职业目标。
 *
 * <h2>装什么</h2>
 * 联系方式、个人简介、全局职业目标（目标职位、职级、行业、城市或工作方式、定位摘要）。
 * 它区别于 {@code com.intelligentresume.careermaterial}：这里是「我是谁、我想找什么」，
 * 那边是「我做过什么」。
 *
 * <h2>两条边界</h2>
 * <ul>
 *   <li><b>联系方式不出服务端</b>：生成简历时由服务端合并进最终产物，<b>不发送给模型</b>；</li>
 *   <li><b>一人一份</b>：由数据库唯一约束保证，不要新增"多档案切换"的旁路而不改约束。</li>
 * </ul>
 *
 * <p>{@code PersonalProfileService} 还负责「从现有简历反推建议」——它只产生<b>建议</b>，
 * 不自动写入档案。
 */
package com.intelligentresume.personalprofile;
